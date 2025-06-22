//package qupath.ext.qust;
//
//import java.awt.image.BufferedImage;
//import java.io.BufferedReader;
//import java.io.File;
//import java.io.FileReader;
//import java.io.IOException;
//import java.nio.file.Files;
//import java.nio.file.Paths;
//import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.Collection;
//import java.util.Collections;
//import java.util.List;
//import java.util.concurrent.BlockingQueue;
//import java.util.concurrent.CompletableFuture;
//import java.util.concurrent.ConcurrentHashMap;
//import java.util.concurrent.ExecutorService;
//import java.util.concurrent.LinkedBlockingQueue;
//import java.util.concurrent.ThreadFactory;
//import java.util.concurrent.TimeUnit;
//import java.util.concurrent.atomic.AtomicBoolean;
//import java.util.function.Predicate;
//import java.util.stream.Collectors;
//import java.util.stream.IntStream;
//
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import com.google.gson.Gson;
//import com.google.gson.JsonArray;
//import com.google.gson.JsonObject;
//
//import ai.djl.Device;
//import ai.djl.ModelException;
//import ai.djl.inference.Predictor;
//import ai.djl.modality.Classifications;
//import ai.djl.modality.cv.Image;
//import ai.djl.modality.cv.ImageFactory;
//import ai.djl.ndarray.NDArray;
//import ai.djl.ndarray.NDList;
//import ai.djl.repository.zoo.Criteria;
//import ai.djl.repository.zoo.ModelNotFoundException;
//import ai.djl.repository.zoo.ZooModel;
//import ai.djl.translate.Batchifier;
//import ai.djl.translate.Translator;
//import ai.djl.translate.TranslatorContext;
//import javafx.beans.property.BooleanProperty;
//import javafx.beans.property.StringProperty;
//import qupath.fx.dialogs.Dialogs;
//import qupath.lib.common.ThreadTools;
//import qupath.lib.gui.prefs.PathPrefs;
//import qupath.lib.gui.scripting.QPEx;
//import qupath.lib.images.ImageData;
//import qupath.lib.images.servers.ImageServer;
//import qupath.lib.images.servers.PixelCalibration;
//import qupath.lib.measurements.MeasurementList;
//import qupath.lib.objects.PathObject;
//import qupath.lib.objects.classes.PathClass;
//import qupath.lib.objects.hierarchy.PathObjectHierarchy;
//import qupath.lib.plugins.AbstractTileableDetectionPlugin;
//import qupath.lib.plugins.ObjectDetector;
//import qupath.lib.plugins.TaskRunner;
//import qupath.lib.plugins.parameters.ParameterList;
//import qupath.lib.regions.RegionRequest;
//import qupath.lib.roi.interfaces.ROI;
//
//public class ObjectClassificationDJL_PC extends AbstractTileableDetectionPlugin<BufferedImage> {
//    private static StringProperty QuSTObjclsModelNameProp = PathPrefs.createPersistentPreference("QuSTObjclsModelName", null);
//    private static BooleanProperty QuSTObjclsDetectionProp = PathPrefs.createPersistentPreference("QuSTObjclsDetection", true);         
//    
//    protected boolean parametersInitialized = false;
//    private static QuSTSetup qustSetup = QuSTSetup.getInstance();
//    private static Logger logger = LoggerFactory.getLogger(ObjectClassificationDJL_PC.class);
//    
//    private static GPUProcessingManager gpuManager;
//    private static final Object GPU_MANAGER_LOCK = new Object();
//    private static volatile int totalObjectsToProcess = 0;
//    private static volatile int processedObjects = 0;
//    
//    private boolean modelNormalized;
//    private float[] modelImageStd;
//    private float[] modelImageMean;
//    private int modelFeatureSizePixels;
//    private double modelPixelSizeMicrons;
//    private List<String> modelLabelList;
//    private String modelName;
//    private double[][] normalizer_w = null;
//    private List<PathObject> availabelObjList;
//    private MacenkoStainingNormalizer normalizer = new MacenkoStainingNormalizer();
//    private ParameterList params;
//    
//    static class WorkItem {
//        final int availableObjectsIndex;
//        final Image image;
//        final CompletableFuture<Classifications> resultFuture;
//        
//        WorkItem(int index, Image image) {
//            this.availableObjectsIndex = index;
//            this.image = image;
//            this.resultFuture = new CompletableFuture<>();
//        }
//    }
//    
//    static class GPUProcessingManager {
//        private final BlockingQueue<WorkItem> workQueue;
//        private final ConcurrentHashMap<Integer, WorkItem> workItemsByIndex;
//        private final ExecutorService gpuExecutor;
//        private final AtomicBoolean running;
//        private final int batchSize;
//        private ZooModel<Image, Classifications> sharedModel;
//        private Predictor<Image, Classifications> predictor;
//        
//        public GPUProcessingManager(String modelPath, List<String> classNames, int featureSize, 
//                                  boolean normalized, float[] imageMean, float[] imageStd, int batchSize) {
//            this.workQueue = new LinkedBlockingQueue<>();
//            this.workItemsByIndex = new ConcurrentHashMap<>();
//            this.running = new AtomicBoolean(true);
//            this.batchSize = batchSize;
//            
//            ThreadFactory threadFactory = ThreadTools.createThreadFactory("gpu-classifier", true);
//            this.gpuExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(threadFactory);
//            
//            try {
//                initializeModel(modelPath, classNames, featureSize, normalized, imageMean, imageStd);
//                startGPUProcessing();
//                logger.info("GPU Processing Manager initialized successfully");
//            } catch (Exception e) {
//                logger.error("Failed to initialize GPU Processing Manager: " + e.getMessage(), e);
//                throw new RuntimeException("GPU initialization failed", e);
//            }
//        }
//        
//        private void initializeModel(String modelPath, List<String> classNames, int featureSize, 
//                                   boolean normalized, float[] imageMean, float[] imageStd) throws ModelException, IOException {
//            CustomImageClassificationTranslator translator = new CustomImageClassificationTranslator(
//                featureSize, normalized, imageMean, imageStd, classNames);
//            Device bestGpu = BestGPUSelector.getBestGPUByFreeMemory();
//            
//            Criteria<Image, Classifications> criteria = Criteria.builder()
//                .setTypes(Image.class, Classifications.class)
//                .optModelPath(Paths.get(modelPath))
//                .optTranslator(translator)
//                .optEngine("PyTorch")
//                .optDevice(bestGpu)
//                .build();
//            
//            try {
//                this.sharedModel = criteria.loadModel();
//                this.predictor = sharedModel.newPredictor();
//                logger.info("PyTorch model loaded successfully: {}", modelPath);
//            } catch (ModelNotFoundException e) {
//                throw new ModelException("PyTorch model not found: " + modelPath, e);
//            }
//        }
//        
//        private void startGPUProcessing() {
//            gpuExecutor.submit(() -> {
//                logger.info("GPU processing thread started");
//                try {
//                    while (running.get() || !workQueue.isEmpty()) {
//                        List<WorkItem> batch = collectBatch();
//                        if (!batch.isEmpty()) {
//                            processBatch(batch);
//                        } else {
//                            Thread.sleep(10);
//                        }
//                    }
//                } catch (InterruptedException e) {
//                    Thread.currentThread().interrupt();
//                    logger.info("GPU processing thread interrupted");
//                } catch (Exception e) {
//                    logger.error("GPU processing thread failed: " + e.getMessage(), e);
//                } finally {
//                    cleanup();
//                }
//            });
//        }
//        
//        private List<WorkItem> collectBatch() throws InterruptedException {
//            List<WorkItem> batch = new ArrayList<>();
//            WorkItem first = workQueue.poll(100, TimeUnit.MILLISECONDS);
//            if (first != null) {
//                batch.add(first);
//                while (batch.size() < batchSize) {
//                    WorkItem item = workQueue.poll();
//                    if (item != null) {
//                        batch.add(item);
//                    } else {
//                        break;
//                    }
//                }
//            }
//            return batch;
//        }
//        
//        private void processBatch(List<WorkItem> batch) {
//            long startTime = System.currentTimeMillis();
//            try {
//                logger.debug("Processing batch of {} items", batch.size());
//                
//                if (batch.size() == 1) {
//                    WorkItem item = batch.get(0);
//                    try {
//                        Classifications result = predictor.predict(item.image);
//                        item.resultFuture.complete(result);
//                    } catch (Exception e) {
//                        logger.warn("Failed to process single item with index {}: {}", item.availableObjectsIndex, e.getMessage());
//                        item.resultFuture.completeExceptionally(e);
//                    }
//                } else {
//                    try {
//                        List<Image> batchImages = batch.stream().map(item -> item.image).collect(Collectors.toList());
//                        List<Classifications> batchResults = predictor.batchPredict(batchImages);
//                        
//                        for (int i = 0; i < batch.size(); i++) {
//                            WorkItem item = batch.get(i);
//                            if (i < batchResults.size()) {
//                                item.resultFuture.complete(batchResults.get(i));
//                            } else {
//                                item.resultFuture.completeExceptionally(new RuntimeException("Batch result missing for item " + i));
//                            }
//                        }
//                    } catch (Exception e) {
//                        logger.error("Batch processing failed: " + e.getMessage(), e);
//                        for (WorkItem item : batch) {
//                            item.resultFuture.completeExceptionally(e);
//                        }
//                    }
//                }
//                
//                long duration = System.currentTimeMillis() - startTime;
//                logger.debug("Batch of {} items processed in {}ms", batch.size(), duration);
//                
//            } catch (Exception e) {
//                logger.error("Batch processing failed: " + e.getMessage(), e);
//                for (WorkItem item : batch) {
//                    item.resultFuture.completeExceptionally(e);
//                }
//            }
//        }
//        
//        public CompletableFuture<Classifications> submitWorkItem(int index, Image image) {
//            WorkItem item = new WorkItem(index, image);
//            workItemsByIndex.put(index, item);
//            
//            try {
//                boolean queued = workQueue.offer(item, 5, TimeUnit.SECONDS);
//                if (!queued) {
//                    item.resultFuture.completeExceptionally(new RuntimeException("GPU queue is full, cannot accept more work"));
//                }
//            } catch (InterruptedException e) {
//                Thread.currentThread().interrupt();
//                item.resultFuture.completeExceptionally(e);
//            }
//            
//            return item.resultFuture;
//        }
//        
//        public void shutdown() {
//            running.set(false);
//            WorkItem item;
//            while ((item = workQueue.poll()) != null) {
//                item.resultFuture.completeExceptionally(new RuntimeException("GPU processor shutdown"));
//            }
//            
//            gpuExecutor.shutdown();
//            try {
//                if (!gpuExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
//                    gpuExecutor.shutdownNow();
//                }
//            } catch (InterruptedException e) {
//                Thread.currentThread().interrupt();
//                gpuExecutor.shutdownNow();
//            }
//        }
//        
//        private void cleanup() {
//            if (predictor != null) predictor.close();
//            if (sharedModel != null) sharedModel.close();
//        }
//    }
//    
//    static class CustomImageClassificationTranslator implements Translator<Image, Classifications> {
//        private final int imageSize;
//        private final float[] normalizer_mean;
//        private final float[] normalizer_std;
//        private final List<String> classNames;
//        
//        public CustomImageClassificationTranslator(int featureSize, boolean normalized, 
//                                                  float[] imageMean, float[] imageStd, List<String> classNames) {
//            this.imageSize = featureSize;
//            this.normalizer_mean = imageMean;
//            this.normalizer_std = imageStd;
//            this.classNames = classNames;
//        }
//        
//        @Override
//        public NDList processInput(TranslatorContext ctx, Image input) {
//            Image resized = input.resize(imageSize, imageSize, true);
//            NDArray array = resized.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
//            
//            long[] shape = array.getShape().getShape();
//            if (shape.length == 3 && shape[2] == 3) {
//                array = array.transpose(2, 0, 1);
//            }
//            
//            array = array.div(255.0f);
//            NDArray mean = ctx.getNDManager().create(normalizer_mean).reshape(3, 1, 1);
//            NDArray std = ctx.getNDManager().create(normalizer_std).reshape(3, 1, 1);
//            array = array.sub(mean).div(std);
//            
//            return new NDList(array);
//        }
//        
//        @Override
//        public Classifications processOutput(TranslatorContext ctx, NDList list) {
//            NDArray probabilities = list.singletonOrThrow();
//            probabilities = probabilities.softmax(-1);
//            float[] probArray = probabilities.toFloatArray();
//            List<Double> probs = new ArrayList<>();
//            for (float prob : probArray) {
//                probs.add((double) prob);
//            }
//            return new Classifications(classNames, probs);
//        }
//        
//        @Override
//        public Batchifier getBatchifier() {
//            return Batchifier.STACK;
//        }
//    }
//    
//    class CellClassifier implements ObjectDetector<BufferedImage> {
//        protected String lastResultDesc = null;
//        private List<PathObject> pathObjects = Collections.synchronizedList(new ArrayList<PathObject>());
//        
//        @Override
//        public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
//            try {
//                QuSTObjclsModelNameProp.set((String)params.getChoiceParameterValue("modelName"));
//                QuSTObjclsDetectionProp.set(params.getBooleanParameterValue("includeProbability"));
//                
//                if (pathROI == null) throw new IOException("Object classification requires a ROI!");
//                if(availabelObjList == null || availabelObjList.size() == 0) throw new IOException("No objects are selected!");
//                
//                ImageServer<BufferedImage> server = (ImageServer<BufferedImage>) imageData.getServer();
//                String serverPath = server.getPath();
//                RegionRequest tileRegion = RegionRequest.createInstance(server.getPath(), 1.0, pathROI);
//                
//                availabelObjList.parallelStream().forEach( objObject -> {
//                    ROI objRoi = objObject.getROI();
//                    int x = (int)(0.5+objRoi.getCentroidX());
//                    int y = (int)(0.5+objRoi.getCentroidY());
//                    
//                    if(tileRegion.contains(x, y, 0, 0)) {
//                        synchronized(pathObjects) {
//                            pathObjects.add(objObject);
//                        }
//                    }
//                });
//                
//                if(pathObjects.size() > 0) {
//                    GPUProcessingManager manager = getGPUManager();
//                    double imagePixelSizeMicrons = imageData.getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
//                    int FeatureSizePixels = (int)(0.5+modelFeatureSizePixels*modelPixelSizeMicrons/imagePixelSizeMicrons);
//                    
//                    List<CompletableFuture<Classifications>> futures = new ArrayList<>();
//                    
//                    for (PathObject pathObj : pathObjects) {
//                        try {
//                            Image djlImage = extractImage(pathObj, server, serverPath, FeatureSizePixels);
//                            int index = availabelObjList.indexOf(pathObj);
//                            CompletableFuture<Classifications> future = manager.submitWorkItem(index, djlImage);
//                            futures.add(future);
//                        } catch (Exception e) {
//                            logger.warn("Failed to extract image for object: " + e.getMessage());
//                            CompletableFuture<Classifications> errorFuture = new CompletableFuture<>();
//                            errorFuture.completeExceptionally(e);
//                            futures.add(errorFuture);
//                        }
//                    }
//                    
//                    // Poll until all THIS tile's objects are complete
//                    while (true) {
//                        boolean allComplete = futures.stream().allMatch(f -> f.isDone());
//                        
//                        if (allComplete) {
//                            int successfullyProcessed = 0;
//                            for (int i = 0; i < futures.size(); i++) {
//                                try {
//                                    Classifications result = futures.get(i).get();
//                                    applyClassificationResults(pathObjects.get(i), result, params);
//                                    successfullyProcessed++;
//                                } catch (Exception e) {
//                                    logger.warn("Failed to get result for object {}: {}", i, e.getMessage());
//                                }
//                            }
//                            
//                            synchronized (GPU_MANAGER_LOCK) {
//                                processedObjects += successfullyProcessed;
//                                logger.debug("Tile processed {} objects. Total processed: {}/{}", 
//                                            successfullyProcessed, processedObjects, totalObjectsToProcess);
//                            }
//                            break;
//                        } else {
//                            try {
//                                Thread.sleep(1000);
//                            } catch (InterruptedException e) {
//                                Thread.currentThread().interrupt();
//                                break;
//                            }
//                        }
//                    }
//                }
//                
//            } catch (Exception e) {
//                logger.error("Detection failed: " + e.getMessage(), e);
//            } finally {
//                System.gc();
//            }
//            
//            return pathObjects;
//        }
//        
//        private Image extractImage(PathObject pathObj, ImageServer<BufferedImage> server, 
//                                  String serverPath, int featureSizePixels) throws IOException {
//            ROI objRoi = pathObj.getROI();
//            
//            int x0 = (int) (0.5 + objRoi.getCentroidX() - ((double)featureSizePixels / 2.0));
//            int y0 = (int) (0.5 + objRoi.getCentroidY() - ((double)featureSizePixels / 2.0));
//            RegionRequest objRegion = RegionRequest.createInstance(serverPath, 1.0, x0, y0, featureSizePixels, featureSizePixels);
//            
//            BufferedImage readImg = (BufferedImage)server.readRegion(objRegion);
//            BufferedImage bufImg = new BufferedImage(readImg.getWidth(), readImg.getHeight(), BufferedImage.TYPE_INT_RGB);
//            bufImg.getGraphics().drawImage(readImg, 0, 0, null);
//            
//            BufferedImage normImg = modelNormalized ? 
//                normalizer.normalizeToReferenceImage(bufImg, normalizer_w, normalizer.targetStainReferenceMatrix, BufferedImage.TYPE_INT_RGB) : bufImg;
//            
//            return ImageFactory.getInstance().fromImage(normImg);
//        }
//        
//        private void applyClassificationResults(PathObject objObject, Classifications result, ParameterList params) {
//            Classifications.Classification topPrediction = result.best();
//            String predictedClass = topPrediction.getClassName();
//            
//            PathClass pc = PathClass.fromString("objcls:" + modelName + ":" + predictedClass);
//            objObject.setPathClass(pc);
//            
//            if (params.getBooleanParameterValue("includeProbability")) {
//                MeasurementList pathObjMeasList = objObject.getMeasurementList();
//                
//                for (Classifications.Classification classification : result.items()) {
//                    String className = classification.getClassName();
//                    double probability = classification.getProbability();
//                    synchronized(pathObjMeasList) {
//                        pathObjMeasList.put("objcls:" + modelName + ":prob:" + className, probability);
//                    }
//                }
//                
//                pathObjMeasList.close();
//            }
//        }
//        
//        public static double getPreferredPixelSizeMicrons(ImageData<BufferedImage> imageData, ParameterList params) {
//            PixelCalibration cal = imageData.getServer().getPixelCalibration();
//            if (cal.hasPixelSizeMicrons()) {
//                return cal.getAveragedPixelSizeMicrons();
//            }
//            return Double.NaN;
//        }
//        
//        @Override
//        public String getLastResultsDescription() {
//            return lastResultDesc;
//        }
//    }
//    
//    private GPUProcessingManager getGPUManager() {
//        if (gpuManager == null) {
//            synchronized (GPU_MANAGER_LOCK) {
//                if (gpuManager == null) {
//                    throw new RuntimeException("GPU manager not initialized. This should not happen!");
//                }
//            }
//        }
//        return gpuManager;
//    }
//    
//    private ParameterList buildParameterList(ImageData<BufferedImage> imageData) { 
//        ParameterList params = new ParameterList();
//        
//        try {             
//            if(!imageData.getServer().getPixelCalibration().hasPixelSizeMicrons()) {
//                Dialogs.showErrorMessage("Error", "Please check the image properties in left panel. Most likely the pixel size is unknown.");
//                throw new Exception("No pixel size information");
//            }
//            
//            List<String> classificationModeNamelList = Files.list(Paths.get(qustSetup.getObjclsModelLocationPath()))
//                        .filter(Files::isRegularFile)
//                        .map(p -> p.getFileName().toString())
//                        .filter(s -> s.endsWith(".pt"))
//                        .map(s -> s.replaceAll("\\.pt", ""))
//                        .collect(Collectors.toList());
//            
//            if(classificationModeNamelList.size() == 0) throw new Exception("No model exist in the model directory.");
//            
//            params = new ParameterList()
//                        .addChoiceParameter("modelName", "Model", QuSTObjclsModelNameProp.get() == null? classificationModeNamelList.get(0): QuSTObjclsModelNameProp.get(), classificationModeNamelList, 
//                        "Choose the model that should be used for object classification")
//                        .addBooleanParameter("includeProbability", "Add prediction/probability as a measurement (enables later filtering). Default: false", QuSTObjclsDetectionProp.get(), "Add probability as a measurement (enables later filtering)")
//                        .addEmptyParameter("")
//                        .addEmptyParameter("Adjust below parameters if GPU resources are limited.")
//                        .addIntParameter("batchSize", "Batch Size in classification (default: 65536)", 65536, null, "Batch size in classification. The larger the faster. However, a larger batch size results larger GPU memory consumption.");           
//            
//        } catch (Exception e) {
//            params = null;
//            e.printStackTrace();
//            logger.error(e.getMessage().toString());
//            Dialogs.showErrorMessage("Error", e.getMessage().toString());
//        } finally {
//            System.gc();
//        }
//        
//        return params;
//    }
//    
//    private double[][] estimate_w (ImageData<BufferedImage> imageData)  {
//        double [][] W = null;
//        
//        try {
//            PathObjectHierarchy hierarchy = imageData.getHierarchy();
//            
//            List<PathObject> selectedAnnotationPathObjectList = Collections.synchronizedList(
//                        hierarchy.getSelectionModel().getSelectedObjects().stream()
//                        .filter(e -> e.isAnnotation()).collect(Collectors.toList()));
//            
//            if (selectedAnnotationPathObjectList.isEmpty())
//                throw new Exception("Missed selected annotations");
//            
//            ImageServer<BufferedImage> server = (ImageServer<BufferedImage>) imageData.getServer();
//            String serverPath = server.getPath();
//            
//            AtomicBoolean success = new AtomicBoolean(true);
//            List<PathObject> allPathObjects = Collections.synchronizedList(new ArrayList<PathObject>());
//            
//            for (PathObject sltdObj : selectedAnnotationPathObjectList) {
//                allPathObjects.addAll(sltdObj.getChildObjects());
//            }
//            
//            if(allPathObjects.size() < qustSetup.getNormalizationSampleSize()) throw new Exception("Number of available object samples is too small."); 
//            
//            Collections.shuffle(allPathObjects);
//            List<PathObject> samplingPathObjects = Collections.synchronizedList(allPathObjects.subList(0, qustSetup.getNormalizationSampleSize()));
//            List<BufferedImage> normalizationSamplingImageList = Collections.synchronizedList(new ArrayList<>());
//            
//            IntStream.range(0, samplingPathObjects.size()).parallel().forEach(i -> {
//                PathObject objObject = samplingPathObjects.get(i);
//                ROI objRoi = objObject.getROI();
//                
//                int x0 = (int) (0.5 + objRoi.getCentroidX() - ((double) modelFeatureSizePixels / 2.0));
//                int y0 = (int) (0.5 + objRoi.getCentroidY() - ((double) modelFeatureSizePixels / 2.0));
//                RegionRequest objRegion = RegionRequest.createInstance(serverPath, 1.0, x0, y0, modelFeatureSizePixels, modelFeatureSizePixels);
//                
//                try {
//                    BufferedImage imgContent = (BufferedImage) server.readRegion(objRegion);
//                    BufferedImage imgBuf = new BufferedImage(imgContent.getWidth(), imgContent.getHeight(), BufferedImage.TYPE_INT_RGB);
//                    imgBuf.getGraphics().drawImage(imgContent, 0, 0, null);
//                    normalizationSamplingImageList.add(imgBuf);
//                } catch (Exception e) {
//                    success.set(false);
//                    e.printStackTrace();
//                }
//            });
//            
//            BufferedImage normalizationSamplingImage = normalizer.concatBufferedImages(normalizationSamplingImageList);
//            W = normalizer.reorderStainsByCosineSimilarity(normalizer.getStainMatrix(normalizationSamplingImage, normalizer.OD_threshold, 1), normalizer.targetStainReferenceMatrix);
//        } catch (Exception e) {
//            Dialogs.showErrorMessage("Error", e.getMessage());
//            e.printStackTrace();
//        } finally {
//            System.gc();
//        }
//        
//        return W;
//    }
//    
//    @Override
//    protected void preprocess(TaskRunner taskRunner, ImageData<BufferedImage> imageData) {
//        try {
//            modelName = (String)getParameterList(imageData).getChoiceParameterValue("modelName");
//            String modelLocationStr = qustSetup.getObjclsModelLocationPath();
//            String modelParamPathStr = Paths.get(modelLocationStr, modelName+"_metadata.json").toString();
//            String modelWeightPathStr = Paths.get(modelLocationStr, modelName+".pt").toString();
//            
//            FileReader resultFileReader = new FileReader(new File(modelParamPathStr));
//            BufferedReader bufferedReader = new BufferedReader(resultFileReader);
//            Gson gson = new Gson();
//            JsonObject jsonObject = gson.fromJson(bufferedReader, JsonObject.class);
//            
//            modelPixelSizeMicrons = jsonObject.get("pixel_size").getAsDouble();
//            modelNormalized = jsonObject.get("normalized").getAsBoolean();
//            
//            JsonArray modelImageMeanJsonAry = jsonObject.getAsJsonArray("image_mean");
//            modelImageMean = new float[modelImageMeanJsonAry.size()];
//            for(int i = 0; i < modelImageMeanJsonAry.size(); i ++) 
//                modelImageMean[i] = modelImageMeanJsonAry.get(i).getAsFloat();
//            
//            JsonArray modelImageStdJsonAry = jsonObject.getAsJsonArray("image_std");
//            modelImageStd = new float[modelImageStdJsonAry.size()];
//            for(int i = 0; i < modelImageStdJsonAry.size(); i ++) 
//                modelImageStd[i] = modelImageStdJsonAry.get(i).getAsFloat();
//            
//            modelFeatureSizePixels = jsonObject.get("image_size").getAsInt();
//            modelLabelList = Arrays.asList(jsonObject.get("label_list").getAsString().split(";"));
//            
//            PathObjectHierarchy hierarchy = imageData.getHierarchy();
//            Collection<PathObject> selectedObjects = hierarchy.getSelectionModel().getSelectedObjects();
//            Predicate<PathObject> pred = p -> selectedObjects.contains(p.getParent());
//            
//            availabelObjList = Collections.synchronizedList(QPEx.getObjects(hierarchy, pred));
//            
//            if(availabelObjList == null || availabelObjList.size() < qustSetup.getNormalizationSampleSize()) 
//                throw new Exception("Requires more samples for estimating H&E staining.");
//            
//            if(modelNormalized) {
//                normalizer_w = estimate_w(imageData);
//            }
//            
//            synchronized (GPU_MANAGER_LOCK) {
//                if (gpuManager != null) {
//                    gpuManager.shutdown();
//                    gpuManager = null;
//                }
//                
//                int batchSize = getParameterList(imageData).getIntParameterValue("batchSize");
//                totalObjectsToProcess = availabelObjList.size();
//                processedObjects = 0;
//                
//                gpuManager = new GPUProcessingManager(modelWeightPathStr, modelLabelList, modelFeatureSizePixels,
//                    modelNormalized, modelImageMean, modelImageStd, batchSize);
//                
//                logger.info("GPU Processing Manager initialized for model: {}. Total objects to process: {}", 
//                           modelName, totalObjectsToProcess);
//            }
//            
//        } catch (Exception e) {
//            e.printStackTrace();
//            Dialogs.showErrorMessage("Error", e.getMessage());
//            
//            synchronized (GPU_MANAGER_LOCK) {
//                if (gpuManager != null) {
//                    gpuManager.shutdown();
//                    gpuManager = null;
//                }
//            }
//        } finally {
//            System.gc();
//        }
//    }
//
//    @Override
//    protected void postprocess(TaskRunner taskRunner, ImageData<BufferedImage> imageData) {
//        // Clean up GPU manager when all processing is complete
//        synchronized (GPU_MANAGER_LOCK) {
//            if (gpuManager != null) {
//                logger.info("All processing complete. Shutting down GPU manager.");
//                gpuManager.shutdown();
//                gpuManager = null;
//            }
//            // Reset counters
//            processedObjects = 0;
//            totalObjectsToProcess = 0;
//        }
//    }
//
//    @Override
//    public ParameterList getDefaultParameterList(ImageData<BufferedImage> imageData) {
//        if (!parametersInitialized) {
//            params = buildParameterList(imageData);
//            parametersInitialized = true;
//        }
//        return params;
//    }
//
//    @Override
//    public String getName() {
//        return "Cell classification based on DJL (Queue-based)";
//    }
//
//    @Override
//    public String getLastResultsDescription() {
//        return "";
//    }
//
//    @Override
//    public String getDescription() {
//        return "Cell subtype classification using queue-based GPU processing to eliminate deadlocks";
//    }
//
//    @Override
//    protected double getPreferredPixelSizeMicrons(ImageData<BufferedImage> imageData, ParameterList params) {
//        return CellClassifier.getPreferredPixelSizeMicrons(imageData, params);
//    }
//
//    @Override
//    protected ObjectDetector<BufferedImage> createDetector(ImageData<BufferedImage> imageData, ParameterList params) {
//        return new CellClassifier();
//    }
//
//    @Override
//    protected int getTileOverlap(ImageData<BufferedImage> imageData, ParameterList params) {
//        return 0;
//    }
//}
//
//
//
//
//
