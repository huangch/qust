package qupath.ext.qust;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

//import javax.imageio.ImageIO;

import org.imgscalr.Scalr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ai.djl.Device;
import ai.djl.ModelException;
import ai.djl.inference.Predictor;
import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.StringProperty;
//import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.ThreadTools;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractTileableDetectionPlugin;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;
/**
 * Multi-GPU queue-based object classification with Deep Java Library (DJL).
 * Creates one GPU thread per available GPU device for maximum performance.
 * Fixed version with proper PathObject-to-result matching.
 * 
 * @author Chao Hui Huang
 */
public class ObjectClassificationDJL_PC_mGPU extends AbstractTileableDetectionPlugin<BufferedImage> {
    private static StringProperty QuSTObjclsModelNameProp = PathPrefs.createPersistentPreference("QuSTObjclsModelName", null);
    private static BooleanProperty QuSTObjclsDetectionProp = PathPrefs.createPersistentPreference("QuSTObjclsDetection", true);         
    
    protected boolean parametersInitialized = false;
    private static QuSTSetup qustSetup = QuSTSetup.getInstance();
    private static Logger logger = LoggerFactory.getLogger(ObjectClassificationDJL_PC_mGPU.class);
    
    // Multi-GPU processing infrastructure
    private static MultiGPUProcessingManager multiGpuManager;
    private static final Object GPU_MANAGER_LOCK = new Object();
    private static volatile int totalObjectsToProcess = 0;
    private static volatile int processedObjects = 0;
    
    // Model configuration
    private boolean modelNormalized;
    private float[] modelImageStd;
    private float[] modelImageMean;
    private int modelFeatureSizePixels;
    private double modelPixelSizeMicrons;
    private List<String> modelLabelList;
    private String modelName;
    private double[][] normalizer_w = null;
    private List<PathObject> availabelObjList;
    private ParameterList params;
    
    /**
     * Work item for queue-based processing - now uses PathObject as key
     */
    static class WorkItem {
        final PathObject pathObject;
        final Image image;
        final CompletableFuture<Classifications> resultFuture;
        
        WorkItem(PathObject pathObject, Image image) {
            this.pathObject = pathObject;
            this.image = image;
            this.resultFuture = new CompletableFuture<>();
        }
    }
    
    /**
     * Multi-GPU Processing Manager - manages multiple GPU threads
     */
    static class MultiGPUProcessingManager {
        private final List<GPUProcessingManager> gpuManagers;
        private final AtomicBoolean running;
        private volatile int currentGpuIndex = 0;
        
        public MultiGPUProcessingManager(String modelPath, List<String> classNames, int featureSize, 
                                       boolean normalized, float[] imageMean, float[] imageStd, int batchSize) {
            this.running = new AtomicBoolean(true);
            this.gpuManagers = new ArrayList<>();
            
            // Get all available GPUs
            List<Device> availableGpus = getAvailableGPUs();
            logger.info("Found {} available GPU(s)", availableGpus.size());
            
//            // Create one GPUProcessingManager per GPU
//            for (int i = 0; i < availableGpus.size(); i++) {
//                Device gpu = availableGpus.get(i);
//                try {
//                    GPUProcessingManager manager = new GPUProcessingManager(
//                        modelPath, classNames, featureSize, normalized, imageMean, imageStd, batchSize, gpu, i);
//                    gpuManagers.add(manager);
//                    logger.info("Initialized GPU manager {} on device: {}", i, gpu);
//                } catch (Exception e) {
//                    logger.error("Failed to initialize GPU manager for device {}: {}", gpu, e.getMessage());
//                }
//            }
//            
//            if (gpuManagers.isEmpty()) {
//                throw new RuntimeException("No GPU managers could be initialized");
//            }
//            
//            logger.info("Multi-GPU Processing Manager initialized with {} GPU(s)", gpuManagers.size());
        
         // Create one GPUProcessingManager per GPU (or CPU if no GPUs)
            for (int i = 0; i < availableGpus.size(); i++) {
                Device device = availableGpus.get(i);
                try {
                    GPUProcessingManager manager = new GPUProcessingManager(
                        modelPath, classNames, featureSize, normalized, imageMean, imageStd, batchSize, device, i);
                    gpuManagers.add(manager);
                    
                    if (device.getDeviceType().equals("cpu")) {
                        logger.info("Initialized CPU manager {} on device: {}", i, device);
                    } else {
                        logger.info("Initialized GPU manager {} on device: {}", i, device);
                    }
                } catch (Exception e) {
                    logger.error("Failed to initialize manager for device {}: {}", device, e.getMessage());
  
                    		
//                    // If this was a GPU and it failed, try CPU as fallback
//                    if (!device.getDeviceType().equals("cpu")) {
//                        logger.warn("GPU {} failed, attempting CPU fallback...", device);
//                        try {
//                            Device cpuDevice = Device.cpu();
//                            GPUProcessingManager cpuManager = new GPUProcessingManager(
//                                modelPath, classNames, featureSize, normalized, imageMean, imageStd, batchSize, cpuDevice, i);
//                            gpuManagers.add(cpuManager);
//                            logger.info("Successfully initialized CPU fallback manager {}", i);
//                            break; // Use CPU fallback, don't try more GPUs
//                        } catch (Exception cpuError) {
//                            logger.error("CPU fallback also failed: {}", cpuError.getMessage());
//                        }
//                    }
                }
            }

            if (gpuManagers.isEmpty()) {
//                throw new RuntimeException("No processing managers could be initialized (neither GPU nor CPU)");
            	int nQuPathThreads = PathPrefs.numCommandThreadsProperty().get();
            	logger.info("No processing GPU managers could be initialized, attempting CPU fallback...");
                
            	for (int i = 0; i < nQuPathThreads; i ++) {
	            	try {
	            		Device cpuDevice = Device.cpu();
	            		GPUProcessingManager cpuManager = new GPUProcessingManager(
	            				modelPath, classNames, featureSize, normalized, imageMean, imageStd, batchSize, cpuDevice, i);
	            		gpuManagers.add(cpuManager);
	            		logger.info("Successfully initialized CPU fallback manager {}", i);
	            	} catch (Exception cpuError) {
	            		logger.error("CPU fallback also failed: {}", cpuError.getMessage());
	            	}  
            	}
            }

            // Count GPU vs CPU managers
            long gpuCount = gpuManagers.stream().filter(m -> !m.assignedDevice.getDeviceType().equals("cpu")).count();
            long cpuCount = gpuManagers.stream().filter(m -> m.assignedDevice.getDeviceType().equals("cpu")).count();

            if (gpuCount > 0) {
                logger.info("Multi-GPU Processing Manager initialized with {} GPU(s) and {} CPU manager(s)", gpuCount, cpuCount);
            } else {
                logger.info("CPU-only Processing Manager initialized with {} CPU manager(s)", cpuCount);
            }
        }
        
        private List<Device> getAvailableGPUs() {
            List<Device> gpus = new ArrayList<>();
            
            logger.info("Starting GPU detection and verification...");
            
            // Try to detect available GPUs by actually testing them
            int maxGpus = 16; // Increased limit for systems with many GPUs
            for (int i = 0; i < maxGpus; i++) {
                try {
                    Device gpu = Device.gpu(i);
                    logger.debug("Testing GPU {}: {}", i, gpu);
                    
                    // CRITICAL: Test the GPU by performing actual operations
                    if (testGpuWithActualOperation(gpu, i)) {
                        gpus.add(gpu);
                        logger.info("✓ GPU {} verified and available: {}", i, gpu);
                    } else {
                        logger.warn("✗ GPU {} exists but not usable, stopping detection", i);
                        break;
                    }
                } catch (Exception e) {
                    logger.info("✗ GPU {} not accessible: {}", i, e.getMessage());
                    break; // Stop searching when we hit an inaccessible GPU
                }
            }
            
            // Fallback to CPU if no GPUs found
            if (gpus.isEmpty()) {
                logger.warn("No GPUs detected or verified, falling back to CPU");
                gpus.add(Device.cpu());
            } else {
                logger.info("GPU detection complete: {} GPU(s) available", gpus.size());
            }
            
            return gpus;
        }
        
        /**
         * Test GPU with actual operations to verify it's truly usable
         */
        private boolean testGpuWithActualOperation(Device gpu, int gpuIndex) {
            try {
                logger.debug("Testing GPU {} with actual tensor operations...", gpuIndex);
                
                // Create a minimal NDManager and try actual GPU operations
                try (ai.djl.ndarray.NDManager testManager = ai.djl.ndarray.NDManager.newBaseManager(gpu)) {
                    // Test 1: Create a tensor on GPU
                    ai.djl.ndarray.NDArray testTensor = testManager.create(new float[]{1.0f, 2.0f, 3.0f, 4.0f});
                    
                    // Test 2: Perform GPU computation
                    ai.djl.ndarray.NDArray result = testTensor.add(1.0f);
                    
                    // Test 3: Memory allocation test
                    ai.djl.ndarray.NDArray largeTensor = testManager.zeros(new ai.djl.ndarray.types.Shape(100, 100));
                    ai.djl.ndarray.NDArray computed = largeTensor.add(result.get(0));
                    
                    // Test 4: GPU memory operations
                    computed.sum(); // Force computation
                    
                    // Test 5: Explicit cleanup to ensure resources are released
                    testTensor.close();
                    result.close();
                    largeTensor.close();
                    computed.close();
                    
                    logger.debug("GPU {} passed all operation tests", gpuIndex);
                    return true;
                    
                } catch (Exception e) {
                    logger.debug("GPU {} failed operation test: {}", gpuIndex, e.getMessage());
                    return false;
                } finally {
                    // Force cleanup after GPU testing
                    if (!gpu.getDeviceType().equals("cpu")) {
                        try {
                            System.gc();
                            Runtime.getRuntime().gc();
                        } catch (Exception e) {
                            logger.debug("GPU test cleanup warning: {}", e.getMessage());
                        }
                    }
                }
                
            } catch (Exception e) {
                logger.debug("GPU {} failed manager creation: {}", gpuIndex, e.getMessage());
                return false;
            }
        }

        
        public CompletableFuture<Classifications> submitWorkItem(PathObject pathObject, Image image) {
            // Round-robin GPU selection for load balancing
            int gpuIndex = currentGpuIndex % gpuManagers.size();
            currentGpuIndex = (currentGpuIndex + 1) % gpuManagers.size();
            
            GPUProcessingManager selectedManager = gpuManagers.get(gpuIndex);
            return selectedManager.submitWorkItem(pathObject, image);
        }
        
        public void shutdown() {
            logger.info("Starting Multi-GPU Processing Manager shutdown...");
            running.set(false);
            
            // Shutdown all GPU managers with proper cleanup
            for (int i = 0; i < gpuManagers.size(); i++) {
                GPUProcessingManager manager = gpuManagers.get(i);
                try {
                    logger.debug("Shutting down GPU manager {}", i);
                    manager.shutdown();
                    
                    // Wait briefly between manager shutdowns to avoid resource conflicts
                    Thread.sleep(100);
                    
                } catch (Exception e) {
                    logger.error("Error shutting down GPU manager {}: {}", i, e.getMessage(), e);
                }
            }
            
            // Clear the list
            gpuManagers.clear();
            
            // Additional cleanup
            try {
                // Force any remaining work items to complete with errors
                System.gc();
                
                // Brief pause to allow final cleanup
                Thread.sleep(200);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            logger.info("Multi-GPU Processing Manager shutdown complete");
        }

        
        public int getGpuCount() {
            return gpuManagers.size();
        }
        
        public boolean isHealthy() {
            return running.get() && !gpuManagers.isEmpty();
        }
    }
    
    /**
     * Single GPU Processing Manager - handles one GPU thread and its work queue
     */
    static class GPUProcessingManager {
        private final BlockingQueue<WorkItem> workQueue;
        private final ConcurrentHashMap<PathObject, WorkItem> workItemsByPathObject;
        private final ExecutorService gpuExecutor;
        private final AtomicBoolean running;
        private final int batchSize;
        private final Device assignedDevice;
        private final int managerId;
        private ZooModel<Image, Classifications> sharedModel;
        private Predictor<Image, Classifications> predictor;
        
        public GPUProcessingManager(String modelPath, List<String> classNames, int featureSize, 
                                  boolean normalized, float[] imageMean, float[] imageStd, int batchSize, 
                                  Device device, int managerId) {
            this.workQueue = new LinkedBlockingQueue<>();
            this.workItemsByPathObject = new ConcurrentHashMap<>();
            this.running = new AtomicBoolean(true);
            this.batchSize = batchSize;
            this.assignedDevice = device;
            this.managerId = managerId;
            
//            ThreadFactory threadFactory = ThreadTools.createThreadFactory("gpu-classifier-" + managerId, true);
            String deviceType = device.getDeviceType().equals("cpu") ? "cpu" : "gpu";
            ThreadFactory threadFactory = ThreadTools.createThreadFactory(deviceType + "-classifier-" + managerId, true);

            this.gpuExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(threadFactory);
            
            try {
                initializeModel(modelPath, classNames, featureSize, normalized, imageMean, imageStd);
                startGPUProcessing();
//                logger.info("GPU Processing Manager {} initialized successfully on device: {}", managerId, device);
                if (device.getDeviceType().equals("cpu")) {
                    logger.info("CPU Processing Manager {} initialized successfully on device: {}", managerId, device);
                } else {
                    logger.info("GPU Processing Manager {} initialized successfully on device: {}", managerId, device);
                }
            } catch (Exception e) {
                logger.error("Failed to initialize GPU Processing Manager {}: {}", managerId, e.getMessage(), e);
                throw new RuntimeException("GPU initialization failed for manager " + managerId, e);
            }
        }
        
        private void initializeModel(String modelPath, List<String> classNames, int featureSize, 
                                   boolean normalized, float[] imageMean, float[] imageStd) throws ModelException, IOException {
            CustomImageClassificationTranslator translator = new CustomImageClassificationTranslator(
                featureSize, normalized, imageMean, imageStd, classNames);
            
            Criteria<Image, Classifications> criteria = Criteria.builder()
                .setTypes(Image.class, Classifications.class)
                .optModelPath(Paths.get(modelPath))
                .optTranslator(translator)
                .optEngine("PyTorch")
                .optDevice(assignedDevice)  // Use assigned device
                .build();
            
            try {
                this.sharedModel = criteria.loadModel();
                this.predictor = sharedModel.newPredictor();
//                logger.info("PyTorch model loaded successfully on GPU {}: {}", managerId, modelPath);
                if (assignedDevice.getDeviceType().equals("cpu")) {
                    logger.info("PyTorch model loaded successfully on CPU {}: {}", managerId, modelPath);
                } else {
                    logger.info("PyTorch model loaded successfully on GPU {}: {}", managerId, modelPath);
                }
            } catch (ModelNotFoundException e) {
                throw new ModelException("PyTorch model not found: " + modelPath, e);
            }
        }
        
        private void startGPUProcessing() {
            gpuExecutor.submit(() -> {
//                logger.info("GPU processing thread {} started on device: {}", managerId, assignedDevice);
            	String deviceType = assignedDevice.getDeviceType().equals("cpu") ? "CPU" : "GPU";
            	logger.info("{} processing thread {} started on device: {}", deviceType, managerId, assignedDevice);

                try {
                    while (running.get() || !workQueue.isEmpty()) {
                        List<WorkItem> batch = collectBatch();
                        if (!batch.isEmpty()) {
                            processBatch(batch);
                        } else {
                            Thread.sleep(10);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.info("GPU processing thread {} interrupted", managerId);
                } catch (Exception e) {
                    logger.error("GPU processing thread {} failed: {}", managerId, e.getMessage(), e);
                } finally {
                    cleanup();
                }
            });
        }
        
        private List<WorkItem> collectBatch() throws InterruptedException {
            List<WorkItem> batch = new ArrayList<>();
            WorkItem first = workQueue.poll(100, TimeUnit.MILLISECONDS);
            if (first != null) {
                batch.add(first);
                while (batch.size() < batchSize) {
                    WorkItem item = workQueue.poll();
                    if (item != null) {
                        batch.add(item);
                    } else {
                        break;
                    }
                }
            }
            return batch;
        }
        
        private void processBatch(List<WorkItem> batch) {
            long startTime = System.currentTimeMillis();
            ai.djl.ndarray.NDManager batchManager = null;
            
            try {
                String deviceType = assignedDevice.getDeviceType().equals("cpu") ? "CPU" : "GPU";
                logger.debug("{} {} processing batch of {} items", deviceType, managerId, batch.size());
                
                // Create a batch-specific NDManager for better memory tracking
                batchManager = ai.djl.ndarray.NDManager.newBaseManager(assignedDevice);
                
                if (batch.size() == 1) {
                    WorkItem item = batch.get(0);
                    try {
                        Classifications result = predictor.predict(item.image);
                        item.resultFuture.complete(result);
                    } catch (Exception e) {
                        logger.warn("{} {} failed to process single item for pathObject {}: {}", deviceType, managerId, item.pathObject, e.getMessage());
                        item.resultFuture.completeExceptionally(e);
                    }
                } else {
                    try {
                        List<Image> batchImages = batch.stream().map(item -> item.image).collect(Collectors.toList());
                        List<Classifications> batchResults = predictor.batchPredict(batchImages);
                        
                        // CRITICAL: Ensure order preservation in batch processing
                        for (int i = 0; i < batch.size(); i++) {
                            WorkItem item = batch.get(i);
                            if (i < batchResults.size()) {
                                item.resultFuture.complete(batchResults.get(i));
                            } else {
                                item.resultFuture.completeExceptionally(new RuntimeException("Batch result missing for item " + i));
                            }
                        }         
                    } catch (Exception e) {
                        logger.error("{} {} batch processing failed: {}", deviceType, managerId, e.getMessage(), e);
                        for (WorkItem item : batch) {
                            item.resultFuture.completeExceptionally(e);
                        }
                    }
                }
                
                long duration = System.currentTimeMillis() - startTime;
                logger.debug("{} {} batch of {} items processed in {}ms", deviceType, managerId, batch.size(), duration);
                
            } catch (Exception e) {
                String deviceType = assignedDevice.getDeviceType().equals("cpu") ? "CPU" : "GPU";
                logger.error("{} {} batch processing failed: {}", deviceType, managerId, e.getMessage(), e);
                for (WorkItem item : batch) {
                    item.resultFuture.completeExceptionally(e);
                }
            } finally {
                // CRITICAL: Close batch manager immediately after processing
                if (batchManager != null) {
                    try {
                        batchManager.close();
                    } catch (Exception e) {
                        logger.debug("Batch manager cleanup warning: {}", e.getMessage());
                    }
                }
                
                // Force immediate GPU memory cleanup after each batch
                if (!assignedDevice.getDeviceType().equals("cpu")) {
                    try {
                        System.gc();
                        Runtime.getRuntime().gc();
                    } catch (Exception e) {
                        logger.debug("Batch GPU memory cleanup warning: {}", e.getMessage());
                    }
                }
            }
        }

        public CompletableFuture<Classifications> submitWorkItem(PathObject pathObject, Image image) {
            WorkItem item = new WorkItem(pathObject, image);
            workItemsByPathObject.put(pathObject, item);
            
            try {
                boolean queued = workQueue.offer(item, 5, TimeUnit.SECONDS);
                if (!queued) {
                    item.resultFuture.completeExceptionally(new RuntimeException("GPU queue is full, cannot accept more work"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                item.resultFuture.completeExceptionally(e);
            }
            
            return item.resultFuture;
        }
        
        public void shutdown() {
            running.set(false);
            WorkItem item;
            while ((item = workQueue.poll()) != null) {
                item.resultFuture.completeExceptionally(new RuntimeException("GPU processor shutdown"));
            }
            
            gpuExecutor.shutdown();
            try {
                if (!gpuExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    gpuExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                gpuExecutor.shutdownNow();
            }
            
            // Force final cleanup
            cleanup();
        }
        
        private void cleanup() {
            try {
                logger.info("Starting cleanup for GPU manager {}", managerId);
                
                // Close predictor first
                if (predictor != null) {
                    try {
                        predictor.close();
                        logger.debug("Predictor closed for GPU manager {}", managerId);
                    } catch (Exception e) {
                        logger.warn("Error closing predictor for GPU {}: {}", managerId, e.getMessage());
                    }
                    predictor = null;
                }
                
                // Close model
                if (sharedModel != null) {
                    try {
                        sharedModel.close();
                        logger.debug("Model closed for GPU manager {}", managerId);
                    } catch (Exception e) {
                        logger.warn("Error closing model for GPU {}: {}", managerId, e.getMessage());
                    }
                    sharedModel = null;
                }
                
                // GPU-specific cleanup
                if (!assignedDevice.getDeviceType().equals("cpu")) {
                    logger.info("Performing GPU memory cleanup for device: {}", assignedDevice);
                    
                    try {
                        // Force device-specific NDManager cleanup
                        try (ai.djl.ndarray.NDManager deviceManager = ai.djl.ndarray.NDManager.newBaseManager(assignedDevice)) {
                            // Create and immediately close to force cleanup
                            deviceManager.create(1.0f).close();
                        }
                        logger.debug("Device-specific NDManager cleanup completed for GPU {}", managerId);
                    } catch (Exception e) {
                        logger.debug("Device manager cleanup warning for GPU {}: {}", managerId, e.getMessage());
                    }
                    
                    // Multiple aggressive GC calls for GPU memory
                    for (int i = 0; i < 5; i++) {
                        System.gc();
                        Runtime.getRuntime().gc();
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    
                    logger.info("GPU memory cleanup completed for device: {}", assignedDevice);
                }
                
            } catch (Exception e) {
                logger.error("Error during GPU manager cleanup: {}", e.getMessage(), e);
            }
        }
    }
    
    /**
     * Custom DJL Translator for image classification
     */
    static class CustomImageClassificationTranslator implements Translator<Image, Classifications> {
        private final int imageSize;
        private final float[] normalizer_mean;
        private final float[] normalizer_std;
        private final List<String> classNames;
        
        public CustomImageClassificationTranslator(int featureSize, boolean normalized, 
                                                  float[] imageMean, float[] imageStd, List<String> classNames) {
            this.imageSize = featureSize;
            this.normalizer_mean = imageMean;
            this.normalizer_std = imageStd;
            this.classNames = classNames;
        }
        
        @Override
        public NDList processInput(TranslatorContext ctx, Image input) {
            Image resized = input.resize(imageSize, imageSize, true);
            NDArray array = resized.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
            
            long[] shape = array.getShape().getShape();
            if (shape.length == 3 && shape[2] == 3) {
                array = array.transpose(2, 0, 1);
            }
            
            array = array.div(255.0f);
            NDArray mean = ctx.getNDManager().create(normalizer_mean).reshape(3, 1, 1);
            NDArray std = ctx.getNDManager().create(normalizer_std).reshape(3, 1, 1);
            array = array.sub(mean).div(std);
            
            return new NDList(array);
        }
        
        @Override
        public Classifications processOutput(TranslatorContext ctx, NDList list) {
            NDArray probabilities = list.singletonOrThrow();
            probabilities = probabilities.softmax(-1);
            float[] probArray = probabilities.toFloatArray();
            List<Double> probs = new ArrayList<>();
            for (float prob : probArray) {
                probs.add((double) prob);
            }
            return new Classifications(classNames, probs);
        }
        
        @Override
        public Batchifier getBatchifier() {
            return Batchifier.STACK;
        }
    }

    /**
     * Cell classifier using queue-based Multi-GPU processing with proper object-to-result matching
     */
    class CellClassifier implements ObjectDetector<BufferedImage> {
        protected String lastResultDesc = null;
        private List<PathObject> pathObjects = Collections.synchronizedList(new ArrayList<PathObject>());
        
        @Override
        public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
            try {
                QuSTObjclsModelNameProp.set((String)params.getChoiceParameterValue("modelName"));
                QuSTObjclsDetectionProp.set(params.getBooleanParameterValue("includeProbability"));
                
                if (pathROI == null) throw new IOException("Object classification requires a ROI!");
                if(availabelObjList == null || availabelObjList.size() == 0) throw new IOException("No objects are selected!");
                
                ImageServer<BufferedImage> server = (ImageServer<BufferedImage>) imageData.getServer();
                String serverPath = server.getPath();
                RegionRequest tileRegion = RegionRequest.createInstance(server.getPath(), 1.0, pathROI);
                
                availabelObjList.parallelStream().forEach( objObject -> {
                    ROI objRoi = objObject.getROI();
                    int x = (int)(0.5+objRoi.getCentroidX());
                    int y = (int)(0.5+objRoi.getCentroidY());
                    
                    if(tileRegion.contains(x, y, 0, 0)) {
                        synchronized(pathObjects) {
                            pathObjects.add(objObject);
                        }
                    }
                });
                
                if(pathObjects.size() > 0) {
                    MultiGPUProcessingManager manager = getGPUManager();
                    double imagePixelSizeMicrons = imageData.getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
                    int FeatureSizePixels = (int)(0.5+modelFeatureSizePixels*modelPixelSizeMicrons/imagePixelSizeMicrons);
                    
                    Map<PathObject, CompletableFuture<Classifications>> objectToFuture = new HashMap<>();
                    
                    for (PathObject pathObj : pathObjects) {
                        try {
                            Image djlImage = extractImage(pathObj, server, serverPath, FeatureSizePixels);
                            CompletableFuture<Classifications> future = manager.submitWorkItem(pathObj, djlImage);
                            objectToFuture.put(pathObj, future);
                        } catch (Exception e) {
                            logger.warn("Failed to extract image for object: " + e.getMessage());
                            CompletableFuture<Classifications> errorFuture = new CompletableFuture<>();
                            errorFuture.completeExceptionally(e);
                            objectToFuture.put(pathObj, errorFuture);
                        }
                    }
                    
                    while (true) {
                        boolean allComplete = objectToFuture.values().stream().allMatch(f -> f.isDone());
                        
                        if (allComplete) {
                            int successfullyProcessed = 0;
                            for (PathObject pathObj : pathObjects) {
                            	
                                try {
                                    Classifications result = objectToFuture.get(pathObj).get();
                                    applyClassificationResults(pathObj, result, params);
                                    successfullyProcessed++;
                                } catch (Exception e) {
                                    logger.warn("Failed to get result for object: {}, error message: {}", pathObj.getName(), e.getMessage());
                                }
                            }
                            
                            synchronized (GPU_MANAGER_LOCK) {
                                processedObjects += successfullyProcessed;
                                logger.debug("Tile processed {} objects. Total processed: {}/{}", 
                                            successfullyProcessed, processedObjects, totalObjectsToProcess);
                            }
                            break;
                        } else {
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }
                
            } catch (Exception e) {
                logger.error("Detection failed: " + e.getMessage(), e);
            } finally {
                System.gc();
            }
            
            return pathObjects;
        }
        
        private Image extractImage(PathObject pathObj, ImageServer<BufferedImage> server, 
                                  String serverPath, int featureSizePixels) throws IOException {
            ROI objRoi = pathObj.getROI();
            
            int x0 = (int) (0.5 + objRoi.getCentroidX() - ((double)featureSizePixels / 2.0));
            int y0 = (int) (0.5 + objRoi.getCentroidY() - ((double)featureSizePixels / 2.0));
            RegionRequest objRegion = RegionRequest.createInstance(serverPath, 1.0, x0, y0, featureSizePixels, featureSizePixels);
            
            BufferedImage readImg = (BufferedImage)server.readRegion(objRegion);
            BufferedImage bufImg = new BufferedImage(readImg.getWidth(), readImg.getHeight(), BufferedImage.TYPE_INT_RGB);
            bufImg.getGraphics().drawImage(readImg, 0, 0, null);
            BufferedImage scaledImg = Scalr.resize(bufImg, modelFeatureSizePixels);
            
            BufferedImage normImg = null;
 
            if (modelNormalized) {
            	double[][][] scaledImgAry = MacenkoStainingNormalizer.convertImageToRGBArray(scaledImg);
            	double[][][] scaledSdaAry = MacenkoStainingNormalizer.rgbToSda(scaledImgAry);
            	double[][][] normaImgAry = MacenkoStainingNormalizer.deconvolutionBasedNormalization(
            			scaledSdaAry, 
            			normalizer_w,  
            			MacenkoStainingNormalizer.targetStainReferenceMatrix, 
            			null, 
            			new String[]{"hematoxylin", "eosin", "null"},
            			null,
            			null
            		);
            	
            	normImg = MacenkoStainingNormalizer.convertRGBArrayToImage(normaImgAry, BufferedImage.TYPE_INT_RGB);
            } else {
            	normImg = scaledImg;
            }
            
            return ImageFactory.getInstance().fromImage(normImg);
        }
        
        private void applyClassificationResults(PathObject objObject, Classifications result, ParameterList params) {
            Classifications.Classification topPrediction = result.best();
            String predictedClass = topPrediction.getClassName();
            
            PathClass pc = PathClass.fromString("objcls:" + modelName + ":" + predictedClass);
            objObject.setPathClass(pc);
            
            if (params.getBooleanParameterValue("includeProbability")) {
                MeasurementList pathObjMeasList = objObject.getMeasurementList();
                
                for (Classifications.Classification classification : result.items()) {
                    String className = classification.getClassName();
                    double probability = classification.getProbability();
                    synchronized(pathObjMeasList) {
                        pathObjMeasList.put("objcls:" + modelName + ":prob:" + className, probability);
                    }
                }
                
                pathObjMeasList.close();
            }
        }
        
        public static double getPreferredPixelSizeMicrons(ImageData<BufferedImage> imageData, ParameterList params) {
            PixelCalibration cal = imageData.getServer().getPixelCalibration();
            if (cal.hasPixelSizeMicrons()) {
                return cal.getAveragedPixelSizeMicrons();
            }
            return Double.NaN;
        }
        
        @Override
        public String getLastResultsDescription() {
            return lastResultDesc;
        }
    }
    
    private MultiGPUProcessingManager getGPUManager() {
        if (multiGpuManager == null) {
            synchronized (GPU_MANAGER_LOCK) {
                if (multiGpuManager == null) {
                    throw new RuntimeException("Multi-GPU manager not initialized. This should not happen!");
                }
            }
        }
        return multiGpuManager;
    }
    
    private ParameterList buildParameterList(ImageData<BufferedImage> imageData) { 
        ParameterList params = new ParameterList();
        
        try {             
            if(!imageData.getServer().getPixelCalibration().hasPixelSizeMicrons()) {
//                Dialogs.showErrorMessage("Error", "Please check the image properties in left panel. Most likely the pixel size is unknown.");
                throw new Exception("No pixel size information");
            }
            
            List<String> classificationModeNamelList = Files.list(Paths.get(qustSetup.getObjclsModelLocationPath()))
                        .filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .filter(s -> s.endsWith(".torchscript.pt"))
                        .map(s -> s.replaceAll("\\.torchscript.pt", ""))
                        .collect(Collectors.toList());
            
            if(classificationModeNamelList.size() == 0) throw new Exception("No model exist in the model directory.");
            
            params = new ParameterList()
                        .addChoiceParameter("modelName", "Model", QuSTObjclsModelNameProp.get() == null? classificationModeNamelList.get(0): QuSTObjclsModelNameProp.get(), classificationModeNamelList, 
                        "Choose the model that should be used for object classification")
                        .addBooleanParameter("includeProbability", "Add prediction/probability as a measurement (enables later filtering). Default: false", QuSTObjclsDetectionProp.get(), "Add probability as a measurement (enables later filtering)")
                        .addEmptyParameter("")
                        .addEmptyParameter("Adjust below parameters if GPU resources are limited.")
                        .addIntParameter("batchSize", "Batch Size in classification (default: 65536)", 65536, null, "Batch size in classification. The larger the faster. However, a larger batch size results larger GPU memory consumption.");           
            
        } catch (Exception e) {
            params = null;
            e.printStackTrace();
            logger.error(e.getMessage().toString());
//            Dialogs.showErrorMessage("Error", e.getMessage().toString());
        } finally {
            System.gc();
        }
        
        return params;
    }
    
    private double[][] estimate_w (ImageData<BufferedImage> imageData)  {
        double [][] W = null;
        
        try {
            PathObjectHierarchy hierarchy = imageData.getHierarchy();
            
            List<PathObject> selectedAnnotationPathObjectList = Collections.synchronizedList(
                        hierarchy.getSelectionModel().getSelectedObjects().stream()
                        .filter(e -> e.isAnnotation()).collect(Collectors.toList()));
            
            if (selectedAnnotationPathObjectList.isEmpty())
                throw new Exception("Missed selected annotations");
            
            ImageServer<BufferedImage> server = (ImageServer<BufferedImage>) imageData.getServer();
            String serverPath = server.getPath();
            
            AtomicBoolean success = new AtomicBoolean(true);
            List<PathObject> allPathObjects = Collections.synchronizedList(new ArrayList<PathObject>());
            
            for (PathObject sltdObj : selectedAnnotationPathObjectList) {
                allPathObjects.addAll(sltdObj.getChildObjects());
            }
            
            if(allPathObjects.size() < qustSetup.getNormalizationSampleSize()) throw new Exception("Number of available object samples is too small."); 
            
            Collections.shuffle(allPathObjects);
            List<PathObject> samplingPathObjects = Collections.synchronizedList(allPathObjects.subList(0, qustSetup.getNormalizationSampleSize()));
            List<BufferedImage> normalizationSamplingImageList = Collections.synchronizedList(new ArrayList<>());
            double imagePixelSizeMicrons = imageData.getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
            int FeatureSizePixels = (int)(0.5+modelFeatureSizePixels*modelPixelSizeMicrons/imagePixelSizeMicrons);
            
            IntStream.range(0, samplingPathObjects.size()).parallel().forEach(i -> {
                PathObject objObject = samplingPathObjects.get(i);
                ROI objRoi = objObject.getROI();
                
                int x0 = (int) (0.5 + objRoi.getCentroidX() - ((double) FeatureSizePixels / 2.0));
                int y0 = (int) (0.5 + objRoi.getCentroidY() - ((double) FeatureSizePixels / 2.0));
                RegionRequest objRegion = RegionRequest.createInstance(serverPath, 1.0, x0, y0, FeatureSizePixels, FeatureSizePixels);
                
                try {
                    BufferedImage imgContent = (BufferedImage) server.readRegion(objRegion);
                    BufferedImage imgBuf = new BufferedImage(imgContent.getWidth(), imgContent.getHeight(), BufferedImage.TYPE_INT_RGB);
                    imgBuf.getGraphics().drawImage(imgContent, 0, 0, null);
                    normalizationSamplingImageList.add(imgBuf);
                } catch (Exception e) {
                    success.set(false);
                    e.printStackTrace();
                }
            });
            
            BufferedImage normalizationSamplingImage = MacenkoStainingNormalizer.concatBufferedImages(normalizationSamplingImageList);
            double[][][] normalizationSamplingImageAry = MacenkoStainingNormalizer.convertImageToRGBArray(normalizationSamplingImage);
            double[][][] normalizationSamplingSdaAry = MacenkoStainingNormalizer.rgbToSda(normalizationSamplingImageAry);
            W = MacenkoStainingNormalizer.separateStainsMacenkoPca(normalizationSamplingSdaAry);
        } catch (Exception e) {
//            Dialogs.showErrorMessage("Error", e.getMessage());
            e.printStackTrace();
        } finally {
            System.gc();
        }
        
        return W;
    }
    
    @Override
    protected void preprocess(TaskRunner taskRunner, ImageData<BufferedImage> imageData) {
        try {
            modelName = (String)getParameterList(imageData).getChoiceParameterValue("modelName");
            String modelLocationStr = qustSetup.getObjclsModelLocationPath();
            String modelParamPathStr = Paths.get(modelLocationStr, modelName+".json").toString();
            String modelWeightPathStr = Paths.get(modelLocationStr, modelName+".pt").toString();
            
            FileReader resultFileReader = new FileReader(new File(modelParamPathStr));
            BufferedReader bufferedReader = new BufferedReader(resultFileReader);
            Gson gson = new Gson();
            JsonObject jsonObject = gson.fromJson(bufferedReader, JsonObject.class);
            
            JsonArray modelImageMeanJsonAry = jsonObject.getAsJsonArray("image_mean");
            modelImageMean = new float[modelImageMeanJsonAry.size()];
            for(int i = 0; i < modelImageMeanJsonAry.size(); i ++) 
                modelImageMean[i] = modelImageMeanJsonAry.get(i).getAsFloat();
            
            JsonArray modelImageStdJsonAry = jsonObject.getAsJsonArray("image_std");
            modelImageStd = new float[modelImageStdJsonAry.size()];
            for(int i = 0; i < modelImageStdJsonAry.size(); i ++) 
                modelImageStd[i] = modelImageStdJsonAry.get(i).getAsFloat();
            
            modelPixelSizeMicrons = jsonObject.get("pixel_size").getAsDouble();
            modelNormalized = jsonObject.get("normalized").getAsBoolean();            
            modelFeatureSizePixels = jsonObject.get("image_size").getAsInt();
            modelLabelList = Arrays.asList(jsonObject.get("label_list").getAsString().split(";"));
            
            PathObjectHierarchy hierarchy = imageData.getHierarchy();
            Collection<PathObject> selectedObjects = hierarchy.getSelectionModel().getSelectedObjects();
            Predicate<PathObject> pred = p -> selectedObjects.contains(p.getParent());
            
            availabelObjList = Collections.synchronizedList(QPEx.getObjects(hierarchy, pred));
            
            if(availabelObjList == null || availabelObjList.size() < qustSetup.getNormalizationSampleSize()) 
                throw new Exception("Requires more samples for estimating H&E staining.");
            
            if(modelNormalized) {
                normalizer_w = estimate_w(imageData);
            }
            
            synchronized (GPU_MANAGER_LOCK) {
                if (multiGpuManager != null) {
                    multiGpuManager.shutdown();
                    multiGpuManager = null;
                }
                
                int batchSize = getParameterList(imageData).getIntParameterValue("batchSize");
                totalObjectsToProcess = availabelObjList.size();
                processedObjects = 0;
                
                multiGpuManager = new MultiGPUProcessingManager(modelWeightPathStr, modelLabelList, modelFeatureSizePixels,
                    modelNormalized, modelImageMean, modelImageStd, batchSize);
                
                logger.info("Multi-GPU Processing Manager initialized for model: {}. Total objects to process: {} across {} GPU(s)", 
                           modelName, totalObjectsToProcess, multiGpuManager.getGpuCount());
            }
            
        } catch (Exception e) {
            e.printStackTrace();
//            Dialogs.showErrorMessage("Error", e.getMessage());
            
            synchronized (GPU_MANAGER_LOCK) {
                if (multiGpuManager != null) {
                    multiGpuManager.shutdown();
                    multiGpuManager = null;
                }
            }
        } finally {
            System.gc();
        }
        
        
//        
//        try {
//        	File imageFile = new File("/workspace/qupath-docker/qupath/qust_scripts/hne_example.png");
//			BufferedImage image = ImageIO.read(imageFile);
//			double[][][] imageAry = MacenkoStainingNormalizer.convertImageToRGBArray(image);
//	        double[][][] imageSdaAry = MacenkoStainingNormalizer.rgbToSda(imageAry);
//	        double[][] debugW = MacenkoStainingNormalizer.separateStainsMacenkoPca(imageSdaAry);
//	        logger.info(modelName);
//		} catch (IOException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
        
        
        

    }

    @Override
    protected void postprocess(TaskRunner taskRunner, ImageData<BufferedImage> imageData) {
        synchronized (GPU_MANAGER_LOCK) {
            if (multiGpuManager != null) {
                logger.info("Starting aggressive GPU cleanup...");
                
                // Step 1: Shutdown the manager normally
                multiGpuManager.shutdown();
                multiGpuManager = null;
                
                // Step 2: Force aggressive DJL cleanup
                forceAggressiveDJLCleanup();
                
                logger.info("Aggressive GPU cleanup completed");
            }
        }
        
        // Clear all references
        availabelObjList = null;
        normalizer_w = null;
        params = null;
        
        // Final cleanup
        System.gc();
    }

    /**
     * Nuclear option: Force DJL to release ALL GPU memory
     */
    private void forceAggressiveDJLCleanup() {
        try {
            logger.info("Executing nuclear GPU memory cleanup...");
            
            // 1. Force close any remaining NDManagers by creating and destroying them
            forceNDManagerCleanup();
            
            // 2. Force PyTorch engine cleanup
            forcePyTorchEngineCleanup();
            
            // 3. Force CUDA memory cleanup if available
            forceCUDAMemoryCleanup();
            
            // 4. System-level cleanup
            forceSystemCleanup();
            
            // 5. Verify cleanup (optional logging)
            logMemoryStatus();
            
        } catch (Exception e) {
            logger.error("Error during aggressive cleanup: {}", e.getMessage(), e);
        }
    }

    /**
     * Force cleanup of all NDManagers
     */
    private void forceNDManagerCleanup() {
        try {
            logger.debug("Forcing NDManager cleanup...");
            
            // Create and immediately close managers on all detected devices
            List<Device> allDevices = new ArrayList<>();
            
            // Add CPU
            allDevices.add(Device.cpu());
            
            // Add all GPUs we can find
            for (int i = 0; i < 16; i++) {
                try {
                    Device gpu = Device.gpu(i);
                    allDevices.add(gpu);
                } catch (Exception e) {
                    break; // No more GPUs
                }
            }
            
            // Force cleanup on each device
            for (Device device : allDevices) {
                try {
                    // Create multiple managers and close them to force cleanup
                    for (int i = 0; i < 5; i++) {
                        try (ai.djl.ndarray.NDManager manager = ai.djl.ndarray.NDManager.newBaseManager(device)) {
                            // Create some tensors and immediately close them
                            NDArray tensor1 = manager.create(new float[100]);
                            NDArray tensor2 = manager.zeros(new ai.djl.ndarray.types.Shape(10, 10));
                            NDArray result = tensor1.get(new ai.djl.ndarray.index.NDIndex("0:10")).add(tensor2.sum());
                            
                            // Force computation and close
                            result.toFloatArray(); // Force evaluation
                            tensor1.close();
                            tensor2.close();
                            result.close();
                        }
                        
                        // Small pause between iterations
                        Thread.sleep(50);
                    }
                    
                    String deviceType = device.getDeviceType().equals("cpu") ? "CPU" : "GPU";
                    logger.debug("Forced cleanup completed for {}: {}", deviceType, device);
                    
                } catch (Exception e) {
                    logger.debug("Device cleanup failed for {}: {}", device, e.getMessage());
                }
            }
            
        } catch (Exception e) {
            logger.debug("NDManager cleanup error: {}", e.getMessage());
        }
    }

    /**
     * Force PyTorch engine cleanup
     */
    private void forcePyTorchEngineCleanup() {
        try {
            logger.debug("Forcing PyTorch engine cleanup...");
            
            // Get PyTorch engine
            ai.djl.engine.Engine engine = ai.djl.engine.Engine.getEngine("PyTorch");
            if (engine != null) {
                // Set properties to force cleanup
                System.setProperty("ai.djl.pytorch.num_interop_threads", "1");
                System.setProperty("ai.djl.pytorch.num_threads", "1");
                System.setProperty("ai.djl.pytorch.graph_optimizer", "false");
                
                // Try to force engine to release resources
                // Note: DJL doesn't provide direct engine cleanup, so we use property manipulation
                logger.debug("PyTorch engine properties set for cleanup");
            }
            
        } catch (Exception e) {
            logger.debug("PyTorch engine cleanup error: {}", e.getMessage());
        }
    }

    /**
     * Force CUDA memory cleanup using JNI calls if available
     */
    private void forceCUDAMemoryCleanup() {
        try {
            logger.debug("Attempting CUDA memory cleanup...");
            
            // Method 1: Try to use PyTorch's CUDA cache clearing
            try {
                // This is a hack - create a manager and try to trigger CUDA cleanup
                for (int i = 0; i < 4; i++) {
                    try {
                        Device gpu = Device.gpu(i);
                        
                        // Create multiple sessions to force CUDA context switching
                        for (int j = 0; j < 3; j++) {
                            try (ai.djl.ndarray.NDManager manager = ai.djl.ndarray.NDManager.newBaseManager(gpu)) {
                                // Create large tensors to force memory allocation/deallocation
                                NDArray large = manager.zeros(new ai.djl.ndarray.types.Shape(1000, 1000));
                                large.toDevice(Device.cpu(), true); // Force copy to CPU
                                large.close();
                                
                                // Force synchronization
                                System.gc();
                                Thread.sleep(50);
                            }
                        }
                        
                        logger.debug("CUDA cleanup attempted for GPU {}", i);
                        
                    } catch (Exception e) {
                        break; // No more GPUs or GPU not available
                    }
                }
                
            } catch (Exception e) {
                logger.debug("CUDA cleanup method 1 failed: {}", e.getMessage());
            }
            
            // Method 2: Try nvidia-ml-py equivalent (system calls)
            try {
                // This is aggressive and may not work in all environments
                ProcessBuilder pb = new ProcessBuilder("nvidia-sml", "--query-gpu=memory.used", "--format=csv,noheader,nounits");
                Process p = pb.start();
                p.waitFor(2, TimeUnit.SECONDS); // Short timeout
                if (p.isAlive()) {
                    p.destroyForcibly();
                }
                logger.debug("NVIDIA SMI memory query attempted");
            } catch (Exception e) {
                logger.debug("NVIDIA SMI not available: {}", e.getMessage());
            }
            
        } catch (Exception e) {
            logger.debug("CUDA cleanup error: {}", e.getMessage());
        }
    }

    /**
     * System-level cleanup
     */
    private void forceSystemCleanup() {
        try {
            logger.debug("Forcing system-level cleanup...");
            
            // Multiple aggressive garbage collection cycles
            for (int i = 0; i < 10; i++) {
                System.gc();
                Runtime.getRuntime().gc();
                
                // Try to force finalization
                System.runFinalization();
                
                // Brief pause between cycles
                Thread.sleep(100);
            }
            
            // Additional JVM cleanup attempts
            try {
                // Force memory reclamation
                Runtime runtime = Runtime.getRuntime();
                long before = runtime.totalMemory() - runtime.freeMemory();
                
                // Multiple cleanup cycles
                for (int i = 0; i < 5; i++) {
                    runtime.gc();
                    System.runFinalization();
                    Thread.sleep(200);
                }
                
                long after = runtime.totalMemory() - runtime.freeMemory();
                long freed = before - after;
                
                if (freed > 0) {
                    logger.debug("System cleanup freed approximately {} MB", freed / (1024 * 1024));
                }
                
            } catch (Exception e) {
                logger.debug("System cleanup measurement failed: {}", e.getMessage());
            }
            
            // Final pause to allow cleanup to complete
            Thread.sleep(500);
            
        } catch (Exception e) {
            logger.debug("System cleanup error: {}", e.getMessage());
        }
    }

    /**
     * Log memory status after cleanup
     */
    private void logMemoryStatus() {
        try {
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            long maxMemory = runtime.maxMemory();
            
            logger.info("Post-cleanup memory status:");
            logger.info("  Used: {} MB", usedMemory / (1024 * 1024));
            logger.info("  Free: {} MB", freeMemory / (1024 * 1024));
            logger.info("  Total: {} MB", totalMemory / (1024 * 1024));
            logger.info("  Max: {} MB", maxMemory / (1024 * 1024));
            logger.info("  Usage: {}%", (double) usedMemory / totalMemory * 100);
            
        } catch (Exception e) {
            logger.debug("Memory status logging failed: {}", e.getMessage());
        }
    }

    /**
     * NUCLEAR OPTION: Call this if nothing else works
     * This will attempt to force-kill any remaining GPU processes
     */
//    private void nuclearGPUCleanup() {
//        logger.warn("Executing NUCLEAR GPU cleanup - this may affect other GPU processes!");
//        
//        try {
//            // Method 1: Try to reset GPU state via nvidia-smi
//            try {
//                ProcessBuilder pb = new ProcessBuilder("nvidia-smi", "--gpu-reset");
//                Process p = pb.start();
//                boolean finished = p.waitFor(5, TimeUnit.SECONDS);
//                if (!finished) {
//                    p.destroyForcibly();
//                    logger.warn("GPU reset command timed out");
//                } else if (p.exitValue() == 0) {
//                    logger.warn("GPU reset successful");
//                }
//            } catch (Exception e) {
//                logger.debug("GPU reset failed: {}", e.getMessage());
//            }
//            
//            // Method 2: Try to kill specific GPU processes (VERY AGGRESSIVE)
//            try {
//                ProcessBuilder pb = new ProcessBuilder("fuser", "-k", "/dev/nvidia*");
//                Process p = pb.start();
//                p.waitFor(3, TimeUnit.SECONDS);
//                if (p.isAlive()) {
//                    p.destroyForcibly();
//                }
//                logger.warn("Attempted to kill GPU processes");
//            } catch (Exception e) {
//                logger.debug("GPU process kill failed: {}", e.getMessage());
//            }
//            
//        } catch (Exception e) {
//            logger.error("Nuclear cleanup failed: {}", e.getMessage());
//        }
//    }

//    /**
//     * Add this to your GPUProcessingManager.cleanup() method as well:
//     */
//    private void enhancedGPUManagerCleanup() {
//        try {
//            if (predictor != null) {
//                predictor.close();
//                predictor = null;
//            }
//            if (sharedModel != null) {
//                sharedModel.close();
//                sharedModel = null;
//            }
//            
//            // Force device-specific cleanup
//            if (!assignedDevice.getDeviceType().equals("cpu")) {
//                // Create multiple temporary managers to force cleanup
//                for (int i = 0; i < 3; i++) {
//                    try (ai.djl.ndarray.NDManager temp = ai.djl.ndarray.NDManager.newBaseManager(assignedDevice)) {
//                        NDArray tensor = temp.create(new float[]{1.0f});
//                        tensor.add(1.0f).close();
//                        tensor.close();
//                    }
//                    Thread.sleep(100);
//                    System.gc();
//                }
//            }
//            
//        } catch (Exception e) {
//            logger.error("Enhanced GPU cleanup failed: {}", e.getMessage());
//        }
//    }

//    private void forceGPUMemoryCleanup() {
//        try {
//            logger.info("Starting aggressive GPU memory cleanup...");
//            
//            // Force DJL Engine cleanup using correct API
//            try {
//                // Get the PyTorch engine and force cleanup
//                ai.djl.engine.Engine engine = ai.djl.engine.Engine.getEngine("PyTorch");
//                if (engine != null) {
//                    logger.debug("Cleaning up PyTorch engine");
//                    // Force PyTorch cleanup by setting properties
//                    System.setProperty("ai.djl.pytorch.num_interop_threads", "1");
//                    System.setProperty("ai.djl.pytorch.num_threads", "1");
//                }
//            } catch (Exception e) {
//                logger.debug("Engine cleanup warning: {}", e.getMessage());
//            }
//            
//            // Force NDManager cleanup - create and close temporary managers to force cleanup
//            try {
//                // Create temporary managers on each device to force cleanup
//                for (int i = 0; i < 4; i++) { // Try up to 4 GPUs
//                    try {
//                        Device gpu = Device.gpu(i);
//                        try (ai.djl.ndarray.NDManager tempManager = ai.djl.ndarray.NDManager.newBaseManager(gpu)) {
//                            // Create a small tensor and immediately close to force GPU cleanup
//                            ai.djl.ndarray.NDArray temp = tempManager.create(1.0f);
//                            temp.close();
//                        }
//                        logger.debug("Forced cleanup for GPU {}", i);
//                    } catch (Exception e) {
//                        // Expected when GPU doesn't exist
//                        break;
//                    }
//                }
//                
//                // Also cleanup CPU manager
//                try (ai.djl.ndarray.NDManager tempManager = ai.djl.ndarray.NDManager.newBaseManager(Device.cpu())) {
//                    ai.djl.ndarray.NDArray temp = tempManager.create(1.0f);
//                    temp.close();
//                }
//                logger.debug("Forced cleanup for CPU manager");
//                
//            } catch (Exception e) {
//                logger.debug("NDManager cleanup warning: {}", e.getMessage());
//            }
//            
//            // Multiple aggressive garbage collection cycles
//            for (int i = 0; i < 5; i++) {
//                System.gc();
//                Runtime.getRuntime().gc();
//                try {
//                    Thread.sleep(200); // Brief pause between cleanup cycles
//                } catch (InterruptedException e) {
//                    Thread.currentThread().interrupt();
//                    break;
//                }
//            }
//            
//            // CUDA-specific cleanup if available
//            try {
//                // Try PyTorch CUDA cache cleanup using correct DJL methods
//                ai.djl.engine.Engine engine = ai.djl.engine.Engine.getEngine("PyTorch");
//                if (engine != null) {
//                    // Force PyTorch to clear CUDA cache by creating and closing a manager
//                    try (ai.djl.ndarray.NDManager tempManager = ai.djl.ndarray.NDManager.newBaseManager()) {
//                        // This forces PyTorch to initialize and then cleanup
//                        tempManager.create(1.0f);
//                    }
//                    logger.debug("PyTorch CUDA cache cleanup attempted");
//                }
//            } catch (Exception e) {
//                logger.debug("CUDA cleanup attempt: {}", e.getMessage());
//            }
//            
//            logger.info("Aggressive GPU memory cleanup completed");
//            
//        } catch (Exception e) {
//            logger.error("Error during aggressive GPU cleanup: {}", e.getMessage(), e);
//        }
//    }


    @Override
    public ParameterList getDefaultParameterList(ImageData<BufferedImage> imageData) {
        if (!parametersInitialized) {
            params = buildParameterList(imageData);
            parametersInitialized = true;
        }
        return params;
    }

    @Override
    public String getName() {
        return "Multi-GPU Cell Classification (DJL Queue-based)";
    }

    @Override
    public String getLastResultsDescription() {
        return "";
    }

    @Override
    public String getDescription() {
        return "Cell subtype classification using multi-GPU queue-based processing with automatic GPU detection and proper object-to-result matching";
    }

    @Override
    protected double getPreferredPixelSizeMicrons(ImageData<BufferedImage> imageData, ParameterList params) {
        return CellClassifier.getPreferredPixelSizeMicrons(imageData, params);
    }

    @Override
    protected ObjectDetector<BufferedImage> createDetector(ImageData<BufferedImage> imageData, ParameterList params) {
        return new CellClassifier();
    }

    @Override
    protected int getTileOverlap(ImageData<BufferedImage> imageData, ParameterList params) {
        return 0;
    }
}
