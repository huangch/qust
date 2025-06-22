///*-
// * #%L
// * This file is part of QuPath.
// * %%
// * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
// * Contact: IP Management (ipmanagement@qub.ac.uk)
// * Copyright (C) 2018 - 2020 QuPath developers, The University of Edinburgh
// * %%
// * QuPath is free software: you can redistribute it and/or modify
// * it under the terms of the GNU General Public License as
// * published by the Free Software Foundation, either version 3 of the
// * License, or (at your option) any later version.
// * 
// * QuPath is distributed in the hope that it will be useful,
// * but WITHOUT ANY WARRANTY; without even the implied warranty of
// * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// * GNU General Public License for more details.
// * 
// * You should have received a copy of the GNU General Public License 
// * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
// * #L%
// */
//
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
//import java.util.concurrent.Semaphore;
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
//import ai.djl.translate.TranslateException;
//import ai.djl.translate.Translator;
//import ai.djl.translate.TranslatorContext;
//import javafx.beans.property.BooleanProperty;
//import javafx.beans.property.StringProperty;
//import qupath.fx.dialogs.Dialogs;
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
///**
// * Default command for DL-based object classification within QuPath.
// * 
// * @author Chao Hui Huang
// *
// */
//public class ObjectClassificationDJL_MT extends AbstractTileableDetectionPlugin<BufferedImage> {
//	private static StringProperty QuSTObjclsModelNameProp = PathPrefs.createPersistentPreference("QuSTObjclsModelName", null);
//	private static BooleanProperty QuSTObjclsDetectionProp = PathPrefs.createPersistentPreference("QuSTObjclsDetection", true);		
//	
//	protected boolean parametersInitialized = false;
//	private static QuSTSetup qustSetup = QuSTSetup.getInstance();
//	private static Logger logger = LoggerFactory.getLogger(ObjectClassificationDJL_MT.class);
//	private static Semaphore semaphore;
//
////	private transient CellClassifier detector;
//	
//	private boolean modelNormalized;
//	private float[] modelImageStd;
//	private float[] modelImageMean;
//	private int modelFeatureSizePixels;
//	private double modelPixelSizeMicrons;
//	private List<String> modelLabelList;
//	private String modelName;
//	private double[][] normalizer_w = null;
//	private List<PathObject> availabelObjList;
//	private MacenkoStainingNormalizer normalizer = new MacenkoStainingNormalizer();
//		
//	private ParameterList params;
//	
//	class CellClassifier implements ObjectDetector<BufferedImage> {
//		protected String lastResultDesc = null;
//		private List<PathObject> pathObjects = Collections.synchronizedList(new ArrayList<PathObject>());;
//	
//		@Override
//		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
//			try {
//				QuSTObjclsModelNameProp.set((String)params.getChoiceParameterValue("modelName"));
//				QuSTObjclsDetectionProp.set(params.getBooleanParameterValue("includeProbability"));
//
//				if (pathROI == null) throw new IOException("Object classification requires a ROI!");
//				if(availabelObjList == null || availabelObjList.size() == 0) throw new IOException("No objects are selected!");
//
//				ImageServer<BufferedImage> server = (ImageServer<BufferedImage>) imageData.getServer();
//				String serverPath = server.getPath();
//				RegionRequest tileRegion = RegionRequest.createInstance(server.getPath(), 1.0, pathROI);
//
//				// Filter objects within the region
//				availabelObjList.parallelStream().forEach( objObject -> {
//					ROI objRoi = objObject.getROI();
//					int x = (int)(0.5+objRoi.getCentroidX());
//					int y = (int)(0.5+objRoi.getCentroidY());
//	
//					if(tileRegion.contains(x, y, 0, 0)) {
//						synchronized(pathObjects) {
//							pathObjects.add(objObject);
//						}
//					}
//				});
//
//				if(pathObjects.size() > 0) {
//					// Load PyTorch model using DJL
//					ZooModel<Image, Classifications> model = loadPyTorchModel();
//					Predictor<Image, Classifications> predictor = model.newPredictor();
//					
//					try {
//						// Calculate feature size in pixels
//						double imagePixelSizeMicrons = imageData.getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
//						int FeatureSizePixels = (int)(0.5+modelFeatureSizePixels*modelPixelSizeMicrons/imagePixelSizeMicrons);
//
//						// Process objects in batches for efficiency
//						int batchSize = params.getIntParameterValue("batchSize");
//						List<List<PathObject>> batches = createBatches(pathObjects, batchSize);
//
//						if(semaphore != null) semaphore.acquire();
//
//						for (List<PathObject> batch : batches) {
////							processBatch(batch, server, serverPath, FeatureSizePixels, predictor, params);
//							processBatchOptimized(batch, server, serverPath, FeatureSizePixels, predictor, params);
//						}
//
//						if(semaphore != null) semaphore.release();
//
//					} finally {
//						predictor.close();
//						model.close();
//					}
//				}
//			}
//			catch (Exception e) {
//				e.printStackTrace();
//			}
//			finally {
//				System.gc();
//			}
//
//			return pathObjects;
//		}
//
//		/**
//		* Load PyTorch model using Deep Java Library
//		*/
//		private ZooModel<Image, Classifications> loadPyTorchModel() throws ModelException, IOException {
//			String modelLocationStr = qustSetup.getObjclsModelLocationPath();
//			String modelPathStr = Paths.get(modelLocationStr, modelName+".pt").toString();
//	
//			// Create custom translator for the model
//			Translator<Image, Classifications> translator = new CustomImageClassificationTranslator();
//		
//			Device bestGpu = BestGPUSelector.getBestGPUByFreeMemory();
//			
//			Criteria<Image, Classifications> criteria = Criteria.builder()
//				.setTypes(Image.class, Classifications.class)
//				.optModelPath(Paths.get(modelPathStr))
//				.optTranslator(translator)
//				.optEngine("PyTorch")
//				.optDevice(bestGpu) // Use GPU if available
////				.optDevice(Device.gpu()) // Use GPU if available
//				.build();
//	
//			try {
//				return criteria.loadModel();
//			} catch (ModelNotFoundException e) {
//				throw new ModelException("PyTorch model not found: " + modelPathStr, e);
//			}
//		}
//
//		/**
//		* Create batches of objects for efficient processing
//		*/
//		private List<List<PathObject>> createBatches(List<PathObject> objects, int batchSize) {
//			List<List<PathObject>> batches = new ArrayList<>();
//			for (int i = 0; i < objects.size(); i += batchSize) {
//				int end = Math.min(i + batchSize, objects.size());
//				batches.add(objects.subList(i, end));
//			}
//			return batches;
//		}
//
//
//		/**
//		* Apply classification results to PathObject
//		*/
//		private void applyClassificationResults(PathObject objObject, Classifications result, ParameterList params) {
//			// Get top prediction
//			Classifications.Classification topPrediction = result.best();
//			String predictedClass = topPrediction.getClassName();
//	
//			// Set path class
//			PathClass pc = PathClass.fromString("objcls:" + modelName + ":" + predictedClass);
//			objObject.setPathClass(pc);
//	
//			// Add probability measurements if requested
//			if (params.getBooleanParameterValue("includeProbability")) {
//				MeasurementList pathObjMeasList = objObject.getMeasurementList();
//		
//				// Add probabilities for all classes
//				for (Classifications.Classification classification : result.items()) {
//					String className = classification.getClassName();
//					double probability = classification.getProbability();
//					synchronized(pathObjMeasList) {
//						pathObjMeasList.put("objcls:" + modelName + ":prob:" + className, probability);
//					}
//				}
//		
//				pathObjMeasList.close();
//			}
//		}
//
//		
//		/**
//		* Custom translator for your PyTorch model
//		*/
//		private class CustomImageClassificationTranslator implements Translator<Image, Classifications> {
//
//		   @Override
//		   public NDList processInput(TranslatorContext ctx, Image input) {
//		       // Resize to model input size
//		       Image resized = input.resize(modelFeatureSizePixels, modelFeatureSizePixels, true);
//		       // Convert to NDArray
//		       NDArray array = resized.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
//		       // Handle dimension order - check what your model expects
//		       // Most PyTorch models expect [C, H, W], so transpose if needed
//		       long[] shape = array.getShape().getShape();
//		       if (shape.length == 3 && shape[2] == 3) {
//		           // Currently [H, W, C] -> transpose to [C, H, W]
//		           array = array.transpose(2, 0, 1);
//		       }
//		       // Normalize to [0, 1]
//		       array = array.div(255.0f);
//		       // Apply mean/std normalization
//		       NDArray mean = ctx.getNDManager().create(modelImageMean).reshape(3, 1, 1);
//		       NDArray std = ctx.getNDManager().create(modelImageStd).reshape(3, 1, 1);
//		       array = array.sub(mean).div(std);
//		       return new NDList(array);
//		   }
//		   @Override
//		   public Classifications processOutput(TranslatorContext ctx, NDList list) {
//		       NDArray probabilities = list.singletonOrThrow();
//		       // Apply softmax if needed
//		       probabilities = probabilities.softmax(-1);
//		       // Convert to probabilities list
//		       float[] probArray = probabilities.toFloatArray();
//		       List<Double> probs = new ArrayList<>();
//		       for (float prob : probArray) {
//		           probs.add((double) prob);
//		       }
//
//				return new Classifications(modelLabelList, probs);
//		   }
//		   @Override
//		   public Batchifier getBatchifier() {
//		       return Batchifier.STACK;
//		   }
//		}
//		
//		/**
//		* Enhanced batch processing method that processes multiple images at once
//		*/
//		private void processBatchOptimized(List<PathObject> batch, ImageServer<BufferedImage> server,
//			String serverPath, int FeatureSizePixels,
//			Predictor<Image, Classifications> predictor,
//			ParameterList params) throws IOException, TranslateException {
//
//			// For true batch processing, you would need to modify the translator
//			// to handle batch inputs. This is a more complex implementation but more efficient.
//	
//			List<Image> batchImages = Collections.synchronizedList(new ArrayList<>());
//			List<Integer> validIndices = Collections.synchronizedList(new ArrayList<>());
//	
//			// Collect all valid images
//			for (int i = 0; i < batch.size(); i++) {
//				PathObject objObject = batch.get(i);
//				try {
//					ROI objRoi = objObject.getROI();
//					int x0 = (int) (0.5 + objRoi.getCentroidX() - ((double)FeatureSizePixels / 2.0));
//					int y0 = (int) (0.5 + objRoi.getCentroidY() - ((double)FeatureSizePixels / 2.0));
//					RegionRequest objRegion = RegionRequest.createInstance(serverPath, 1.0, x0, y0, FeatureSizePixels, FeatureSizePixels);
//	
//					BufferedImage readImg = (BufferedImage)server.readRegion(objRegion);
//					BufferedImage bufImg = new BufferedImage(readImg.getWidth(), readImg.getHeight(), BufferedImage.TYPE_INT_RGB);
//					bufImg.getGraphics().drawImage(readImg, 0, 0, null);
//					
//					BufferedImage normImg = modelNormalized? 
//							normalizer.normalizeToReferenceImage(
//									bufImg, 
//									normalizer_w, 
//									normalizer.targetStainReferenceMatrix,
//									BufferedImage.TYPE_INT_RGB
//							):
//							bufImg;
//					
//					Image djlImage = ImageFactory.getInstance().fromImage(normImg);
//					batchImages.add(djlImage);
//					validIndices.add(pathObjects.indexOf(objObject));
//	
//				} catch (IOException e) {
//					logger.warn("Failed to read image patch: " + e.getMessage());
//				}
//			}
//	
//			// Process all images (currently one by one, but could be optimized for batch processing)
//			for (int i = 0; i < batchImages.size(); i++) {
//				try {
//					Classifications result = predictor.predict(batchImages.get(i));
//					PathObject objObject = pathObjects.get(validIndices.get(i));
//					applyClassificationResults(objObject, result, params);
//				} catch (TranslateException e) {
//					logger.warn("Failed to classify object: " + e.getMessage());
//				}
//			}
//		}
//		
//		
//		public static double getPreferredPixelSizeMicrons(ImageData<BufferedImage> imageData, ParameterList params) {
//			PixelCalibration cal = imageData.getServer().getPixelCalibration();
//			if (cal.hasPixelSizeMicrons()) {
//				return cal.getAveragedPixelSizeMicrons();
//			}
//			return Double.NaN;
//		}
//		
//		@Override
//		public String getLastResultsDescription() {
//			return lastResultDesc;
//		}
//	}
//	
//	private ParameterList buildParameterList(ImageData<BufferedImage> imageData) { 
//		ParameterList params = new ParameterList();
//
//		try {			
//			if(!imageData.getServer().getPixelCalibration().hasPixelSizeMicrons()) {
//				Dialogs.showErrorMessage("Error", "Please check the image properties in left panel. Most likely the pixel size is unknown.");
//				throw new Exception("No pixel size information");
//			}
//	        
//			List<String> classificationModeNamelList = Files.list(Paths.get(qustSetup.getObjclsModelLocationPath()))
//					.filter(Files::isRegularFile)
//            	    .map(p -> p.getFileName().toString())
//            	    .filter(s -> s.endsWith(".pt"))
//            	    .map(s -> s.replaceAll("\\.pt", ""))
//            	    .collect(Collectors.toList());
//
//			if(classificationModeNamelList.size() == 0) throw new Exception("No model exist in the model directory.");
//			
//			params = new ParameterList()
//					.addChoiceParameter("modelName", "Model", QuSTObjclsModelNameProp.get() == null? classificationModeNamelList.get(0): QuSTObjclsModelNameProp.get(), classificationModeNamelList, 
//					"Choose the model that should be used for object classification")
//					.addBooleanParameter("includeProbability", "Add prediction/probability as a measurement (enables later filtering). Default: false", QuSTObjclsDetectionProp.get(), "Add probability as a measurement (enables later filtering)")
//					.addEmptyParameter("")
//					.addEmptyParameter("Adjust below parameters if GPU resources are limited.")
//					.addIntParameter("batchSize", "Batch Size in classification (default: 128)", 128, null, "Batch size in classification. The larger the faster. However, a larger batch size results larger GPU memory consumption.")		
//					.addIntParameter("maxThread", "Max number of parallel threads (0: using qupath setup)", 1, null, "Max number of parallel threads (0: using qupath setup)");	
//					
//		} catch (Exception e) {
//			params = null;
//			
//			e.printStackTrace();
//			logger.error(e.getMessage().toString());
//			Dialogs.showErrorMessage("Error", e.getMessage().toString());
//		} finally {
//		    System.gc();
//		}
//		
//		return params;
//	}
//	
//	
//	private double[][] estimate_w (ImageData<BufferedImage> imageData)  {
//		double [][] W = null;
//		
//		try {
//			PathObjectHierarchy hierarchy = imageData.getHierarchy();
//
//			List<PathObject> selectedAnnotationPathObjectList = Collections.synchronizedList(
//					hierarchy
//					.getSelectionModel()
//					.getSelectedObjects()
//					.stream()
//					.filter(e -> e.isAnnotation())
//					.collect(Collectors.toList())
//					);
//
//			if (selectedAnnotationPathObjectList.isEmpty())
//				throw new Exception("Missed selected annotations");
//
//			ImageServer<BufferedImage> server = (ImageServer<BufferedImage>) imageData.getServer();
//			String serverPath = server.getPath();
//
//			AtomicBoolean success = new AtomicBoolean(true);
//			
//			List<PathObject> allPathObjects = Collections.synchronizedList(new ArrayList<PathObject>());
//
//			for (PathObject sltdObj : selectedAnnotationPathObjectList) {
//				allPathObjects.addAll(sltdObj.getChildObjects());
//			}
//
//			if(allPathObjects.size() < qustSetup.getNormalizationSampleSize()) throw new Exception("Number of available object samples is too small."); 
//			
//			Collections.shuffle(allPathObjects);
//			List<PathObject> samplingPathObjects = Collections.synchronizedList(allPathObjects.subList(0, qustSetup.getNormalizationSampleSize()));
//
//			List<BufferedImage> normalizationSamplingImageList = Collections.synchronizedList(new ArrayList<>());
//			
//			IntStream.range(0, samplingPathObjects.size()).parallel().forEach(i -> {
////			for(int i = 0; i < samplingPathObjects.size(); i ++) {
//				PathObject objObject = samplingPathObjects.get(i);
//				ROI objRoi = objObject.getROI();
//
//				int x0 = (int) (0.5 + objRoi.getCentroidX() - ((double) modelFeatureSizePixels / 2.0));
//				int y0 = (int) (0.5 + objRoi.getCentroidY() - ((double) modelFeatureSizePixels / 2.0));
//				RegionRequest objRegion = RegionRequest.createInstance(serverPath, 1.0, x0, y0,
//						modelFeatureSizePixels, modelFeatureSizePixels);
//
//				try {
//					BufferedImage imgContent;
//					imgContent = (BufferedImage) server.readRegion(objRegion);
//					BufferedImage imgBuf = new BufferedImage(imgContent.getWidth(), imgContent.getHeight(), BufferedImage.TYPE_INT_RGB);
//					imgBuf.getGraphics().drawImage(imgContent, 0, 0, null);
//					normalizationSamplingImageList.add(imgBuf);
//					
//				} catch (Exception e) {
//					success.set(false);
//					e.printStackTrace();
//				}
//			});
////			}
//			
//			BufferedImage normalizationSamplingImage = normalizer.concatBufferedImages(normalizationSamplingImageList);
//			W = normalizer.reorderStainsByCosineSimilarity(normalizer.getStainMatrix(normalizationSamplingImage, normalizer.OD_threshold, 1), normalizer.targetStainReferenceMatrix);
//		} catch (Exception e) {
//			Dialogs.showErrorMessage("Error", e.getMessage());
//			e.printStackTrace();
//		} finally {
//			System.gc();
//		}
//		
//		return W;
//	}
//	
//	
//	@Override
//	protected void preprocess(TaskRunner taskRunner, ImageData<BufferedImage> imageData) {
//		try {
//			modelName = (String)getParameterList(imageData).getChoiceParameterValue("modelName");
//			String modelLocationStr = qustSetup.getObjclsModelLocationPath();
////			String modelWeightPathStr = Paths.get(modelLocationStr, modelName+".pt").toString();
//			String modelParamPathStr = Paths.get(modelLocationStr, modelName+"_metadata.json").toString();
//				
//	        FileReader resultFileReader = new FileReader(new File(modelParamPathStr));
//			BufferedReader bufferedReader = new BufferedReader(resultFileReader);
//			Gson gson = new Gson();
//			JsonObject jsonObject = gson.fromJson(bufferedReader, JsonObject.class);
//			
//			modelPixelSizeMicrons = jsonObject.get("pixel_size").getAsDouble();
//			modelNormalized = jsonObject.get("normalized").getAsBoolean();
//			modelNormalized = jsonObject.get("normalized").getAsBoolean();
//			
//			JsonArray modelImageMeanJsonAry = jsonObject.getAsJsonArray("image_mean");
//			modelImageMean = new float[modelImageMeanJsonAry.size()];
//			for(int i = 0; i < modelImageMeanJsonAry.size(); i ++) 
//				modelImageMean[i] = modelImageMeanJsonAry.get(i).getAsFloat();
//			
//			JsonArray modelImageStdJsonAry = jsonObject.getAsJsonArray("image_std");
//			modelImageStd = new float[modelImageStdJsonAry.size()];
//			for(int i = 0; i < modelImageStdJsonAry.size(); i ++) 
//				modelImageStd[i] = modelImageStdJsonAry.get(i).getAsFloat();
//			
//			modelFeatureSizePixels = jsonObject.get("image_size").getAsInt();
//			modelLabelList = Arrays.asList(jsonObject.get("label_list").getAsString().split(";"));
//			
//			PathObjectHierarchy hierarchy = imageData.getHierarchy();
//			Collection<PathObject> selectedObjects = hierarchy.getSelectionModel().getSelectedObjects();
//			Predicate<PathObject> pred = p -> selectedObjects.contains(p.getParent());
//			
//			availabelObjList = Collections.synchronizedList(QPEx.getObjects(hierarchy, pred));
//			
//			if(availabelObjList == null || availabelObjList.size() < qustSetup.getNormalizationSampleSize()) throw new Exception("Requires more samples for estimating H&E staining.");
//			
//			int maxThread = getParameterList(imageData).getIntParameterValue("maxThread");
//			semaphore = maxThread > 0? new Semaphore(maxThread): null;
//			
//			if(modelNormalized) normalizer_w = estimate_w(imageData);
//		} catch (Exception e) {
//			e.printStackTrace();
//			
//			Dialogs.showErrorMessage("Error", e.getMessage());
//		} finally {
//		    System.gc();
//		}
//	}	
//
//
//	@Override
//	public ParameterList getDefaultParameterList(ImageData<BufferedImage> imageData) {
//		if (!parametersInitialized) {
//			params = buildParameterList(imageData);
//		}
//	
//		return params;
//	}
//
//	
//	@Override
//	public String getName() {
//		return "Cell classification based on DJL";
//	}
//
//	
//	@Override
//	public String getLastResultsDescription() {
////		return detector == null ? "" : detector.getLastResultsDescription();
//		return "";
//	}
//
//	
//	@Override
//	public String getDescription() {
//		return "Cell subtype classification based on machine leatning algorithms";
//	}
//
//
//	@Override
//	protected double getPreferredPixelSizeMicrons(ImageData<BufferedImage> imageData, ParameterList params) {
//		return CellClassifier.getPreferredPixelSizeMicrons(imageData, params);
//	}
//
//
//	@Override
//	protected ObjectDetector<BufferedImage> createDetector(ImageData<BufferedImage> imageData, ParameterList params) {
//		return new CellClassifier();
//	}
//		
//	
//	@Override
//	protected int getTileOverlap(ImageData<BufferedImage> imageData, ParameterList params) {
//		return 0;
//	}
//}
