/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2020 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.ext.qust;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.commons.io.FileUtils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.StringProperty;
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
//import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * Default command for DL-based object classification within QuPath.
 * 
 * @author Chao Hui Huang
 *
 */
public class ObjectClassification extends AbstractTileableDetectionPlugin<BufferedImage> {
	private static QuSTSetup qustSetup = QuSTSetup.getInstance();
	private static Logger logger = LoggerFactory.getLogger(ObjectClassification.class);
	private static StringProperty QuSTObjclsModelNameProp = PathPrefs.createPersistentPreference("QuSTObjclsModelName", null);
	private static BooleanProperty QuSTObjclsDetectionProp = PathPrefs.createPersistentPreference("QuSTObjclsDetection", true);		
	
	protected boolean parametersInitialized = false;

	private transient CellClassifier detector;
	
	private int modelFeatureSizePixels;
	private double modelPixelSizeMicrons;
	private boolean modelNormalized;
	private List<String> modelLabelList;
	private String modelName;
	private List<PathObject> availabelObjList;
	
	private Semaphore semaphore;
	private ParameterList params;
	private double[] normalizer_w = null;
	private AtomicInteger hackDigit = new AtomicInteger(0);
	private String imgFmt = qustSetup.getImageFileFormat().trim().charAt(0) == '.'? qustSetup.getImageFileFormat().trim().substring(1): qustSetup.getImageFileFormat().trim();
	
	class CellClassifier implements ObjectDetector<BufferedImage> {
		protected String lastResultDesc = null;
		private List<PathObject> pathObjects = Collections.synchronizedList(new ArrayList<PathObject>());;
		
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
				
//		    	pathObjects = Collections.synchronizedList(new ArrayList<PathObject>());
		    	
	    	
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
					// Create a temporary directory for imageset
					String uuid = UUID.randomUUID().toString().replace("-", "")+hackDigit.getAndIncrement()+tileRegion.getMinX()+tileRegion.getMinY();
					
					Path imageSetPath = Files.createTempDirectory("QuST-classification_imageset-" + uuid + "-");
					String imageSetPathString = imageSetPath.toAbsolutePath().toString();
//                    imageSetPath.toFile().deleteOnExit();
        			
                    Path resultPath = Files.createTempFile("QuST-classification_result-" + uuid + "-", ".json");
                    String resultPathString = resultPath.toAbsolutePath().toString();
//                    resultPath.toFile().deleteOnExit();

        			String modelLocationStr = qustSetup.getObjclsModelLocationPath();
        			String modelPathStr = Paths.get(modelLocationStr, modelName+".pt").toString();
        			
                    double imagePixelSizeMicrons = imageData.getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
                    int FeatureSizePixels = (int)(0.5+modelFeatureSizePixels*modelPixelSizeMicrons/imagePixelSizeMicrons);
                                        	
                    IntStream.range(0, pathObjects.size()).forEach(i -> { 
                    // for(int i = 0; i < pathObjects.size(); i ++) {
						PathObject objObject = pathObjects.get(i);
						ROI objRoi = objObject.getROI();
					    int x0 = (int) (0.5 + objRoi.getCentroidX() - ((double)FeatureSizePixels / 2.0));
					    int y0 = (int) (0.5 + objRoi.getCentroidY() - ((double)FeatureSizePixels / 2.0));
					    RegionRequest objRegion = RegionRequest.createInstance(serverPath, 1.0, x0, y0, FeatureSizePixels, FeatureSizePixels);
						
						try {
							// Read image patches from server
							BufferedImage readImg = (BufferedImage)server.readRegion(objRegion);
							BufferedImage bufImg = new BufferedImage(readImg.getWidth(), readImg.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
							bufImg.getGraphics().drawImage(readImg, 0, 0, null);
							
							//  Assign a file name by sequence
							String imageFileName = Integer.toString(i)+"."+imgFmt;
							
							// Obtain the absolute path of the given image file name (with the predefined temporary imageset path)
							Path imageFilePath = Paths.get(imageSetPathString, imageFileName);
							
							// Make the image file
							File imageFile = new File(imageFilePath.toString());
							ImageIO.write(bufImg, imgFmt, imageFile);
						} 
						catch (IOException e) {
							e.printStackTrace();
						}
					});
                    // }
					
                    if(semaphore != null) semaphore.acquire();
                    
					// Create command to run
			        VirtualEnvironmentRunner veRunner;
			        veRunner = new VirtualEnvironmentRunner(qustSetup.getEnvironmentNameOrPath(), qustSetup.getEnvironmentType(), ObjectClassification.class.getSimpleName());
				
			        // This is the list of commands after the 'python' call
			        String script_path = Paths.get(qustSetup.getSptx2ScriptPath(), "classification.py").toString();
			        List<String> QuSTArguments = new ArrayList<>(Arrays.asList("-W", "ignore", script_path, "eval", resultPathString));
			        
			        QuSTArguments.add("--model_file");
			        QuSTArguments.add(modelPathStr);
			        veRunner.setArguments(QuSTArguments);
			        
			        QuSTArguments.add("--image_path");
			        QuSTArguments.add(imageSetPathString);
			        veRunner.setArguments(QuSTArguments);

			        QuSTArguments.add("--image_format");
			        QuSTArguments.add(imgFmt);
			        veRunner.setArguments(QuSTArguments);
			        
			        QuSTArguments.add("--batch_size");
			        QuSTArguments.add(params.getIntParameterValue("batchSize").toString());
			        veRunner.setArguments(QuSTArguments);
			        
			        if(modelNormalized) {
				        QuSTArguments.add("--normalizer_w");
				        QuSTArguments.add(String.join(" ", Arrays.stream(normalizer_w).boxed().map(Object::toString).collect(Collectors.toList())));
				        veRunner.setArguments(QuSTArguments);
			        }
			        
			        // Finally, we can run the command
			        String[] logs = veRunner.runCommand();
			        for (String log : logs) logger.info(log);
			        // logger.info("Object classification command finished running");
					
					if(semaphore != null) semaphore.release();
					
					FileReader resultFileReader = new FileReader(new File(resultPathString));
					BufferedReader bufferedReader = new BufferedReader(resultFileReader);
					Gson gson = new Gson();
					JsonObject jsonObject = gson.fromJson(bufferedReader, JsonObject.class);
					
					Boolean ve_success = gson.fromJson(jsonObject.get("success"), new TypeToken<Boolean>(){}.getType());
					if(!ve_success) throw new Exception("classification.py returned failed");
					
					List<Double> ve_predicted = gson.fromJson(jsonObject.get("predicted"), new TypeToken<List<Double>>(){}.getType());
					if(ve_predicted == null) throw new Exception("classification.py returned null");
					if(ve_predicted.size() != pathObjects.size()) throw new Exception("classification.py returned wrong size");
					
					List<List<Double>> ve_prob_dist = gson.fromJson(jsonObject.get("probability"), new TypeToken<List<List<Double>>>(){}.getType());
					
					if(ve_prob_dist == null) throw new Exception("classification.py returned null");
					if(ve_prob_dist.size() != pathObjects.size()) throw new Exception("classification.py returned wrong size");
					
					IntStream.range(0, ve_predicted.size()).parallel().forEach(i -> {
//					for(int ii = 0; ii < ve_predicted.size(); ii ++) {
//						int i=ii;
						PathClass pc = PathClass.fromString("objcls:"+modelName+":"+modelLabelList.get(ve_predicted.get(i).intValue()));
						pathObjects.get(i).setPathClass(pc);
						
						if(params.getBooleanParameterValue("includeProbability")) {
							MeasurementList pathObjMeasList = pathObjects.get(i).getMeasurementList();
//							pathObjMeasList.put("objcls:"+modelName+":pred", ve_predicted.get(i).intValue());  
							
							IntStream.range(0, modelLabelList.size()).parallel().forEach(k -> {
//							for(int kk = 0; kk < modelLabelList.size(); kk ++) {
//								int k = kk;
								synchronized(pathObjMeasList) {
									pathObjMeasList.put("objcls:"+modelName+":prob:"+modelLabelList.get(k), ve_prob_dist.get(i).get(k));  
								}
							});
//							}
							
							pathObjMeasList.close();
						}
					});
//					}
					
					
					if(imageSetPath != null) {
//	                	imageSetPath.toFile().delete();
	                	FileUtils.deleteDirectory(imageSetPath.toFile());
	                }
	                if(resultPath != null) {
	                	resultPath.toFile().delete();		
//	                	FileUtils.deleteDirectory(resultPath.toFile());
	                }
				}
		    }
			catch (Exception e) {				    	
				e.printStackTrace();
				
			}
		    finally {
//                if(imageSetPath != null) {
////                	imageSetPath.toFile().delete();
//                	FileUtils.deleteDirectory(imageSetPath.toFile());
//                }
//                if(resultPath != null) {
////                	resultPath.toFile().delete();		
//                	FileUtils.deleteDirectory(resultPath.toFile());
//                }
                
                System.gc();
		    }

			return pathObjects;
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
	
	
	private ParameterList buildParameterList(ImageData<BufferedImage> imageData) { 
			
		ParameterList params = new ParameterList();

		try {			
			if(!imageData.getServer().getPixelCalibration().hasPixelSizeMicrons()) {
//				Dialogs.showErrorMessage("Error", "Please check the image properties in left panel. Most likely the pixel size is unknown.");
				throw new Exception("No pixel size information");
			}
	        
			List<String> classificationModeNamelList = Files.list(Paths.get(qustSetup.getObjclsModelLocationPath()))
					.filter(Files::isRegularFile)
            	    .map(p -> p.getFileName().toString())
            	    .filter(s -> s.endsWith(".pt"))
            	    .filter(s -> !s.contains(".torchscript."))
            	    .map(s -> s.replaceAll("\\.pt", ""))
            	    .collect(Collectors.toList());

			if(classificationModeNamelList.size() == 0) throw new Exception("No model exist in the model directory.");
			
			params = new ParameterList()
					.addChoiceParameter("modelName", "Model", QuSTObjclsModelNameProp.get() == null? classificationModeNamelList.get(0): QuSTObjclsModelNameProp.get(), classificationModeNamelList, 
					"Choose the model that should be used for object classification")
					.addBooleanParameter("includeProbability", "Add prediction/probability as a measurement (enables later filtering). Default: false", QuSTObjclsDetectionProp.get(), "Add probability as a measurement (enables later filtering)")
					.addEmptyParameter("")
					.addEmptyParameter("Adjust below parameters if GPU resources are limited.")
					.addIntParameter("batchSize", "Batch Size in classification (default: 128)", 1024, null, "Batch size in classification. The larger the faster. However, a larger batch size results larger GPU memory consumption.")		
					.addIntParameter("maxThread", "Max number of parallel threads (0: using qupath setup)", 1, null, "Max number of parallel threads (0: using qupath setup)");	
					
		} catch (Exception e) {
			params = null;
			
			e.printStackTrace();
			logger.error(e.getMessage().toString());
//			Dialogs.showErrorMessage("Error", e.getMessage().toString());
		} finally {
		    System.gc();
		}
		
		return params;
	}
	
	
	private double[] estimate_w (ImageData<BufferedImage> imageData)  {
		double [] W = null;
		
		try {
			PathObjectHierarchy hierarchy = imageData.getHierarchy();

			List<PathObject> selectedAnnotationPathObjectList = Collections.synchronizedList(
					hierarchy
					.getSelectionModel()
					.getSelectedObjects()
					.stream()
					.filter(e -> e.isAnnotation())
					.collect(Collectors.toList())
					);

			if (selectedAnnotationPathObjectList.isEmpty())
				throw new Exception("Missed selected annotations");

			ImageServer<BufferedImage> server = (ImageServer<BufferedImage>) imageData.getServer();
			String serverPath = server.getPath();

			AtomicBoolean success = new AtomicBoolean(true);
			
			String uuid = UUID.randomUUID().toString().replace("-", "")+hackDigit.getAndIncrement();
			
			Path imageSetPath = Files.createTempDirectory("QuST-estimate_w-" + uuid + "-");
            String imageSetPathString = imageSetPath.toAbsolutePath().toString();
//            imageSetPath.toFile().deleteOnExit();
            
			Path resultPath = Files.createTempFile("QuST-classification_result-" + uuid + "-", ".json");
            String resultPathString = resultPath.toAbsolutePath().toString();
//            resultPath.toFile().deleteOnExit();
			
			List<PathObject> allPathObjects = Collections.synchronizedList(new ArrayList<PathObject>());

			for (PathObject sltdObj : selectedAnnotationPathObjectList) {
				allPathObjects.addAll(sltdObj.getChildObjects());
			}

			
			if(allPathObjects.size() < qustSetup.getNormalizationSampleSize()) throw new Exception("Number of available object samples is too small."); 
			
			Collections.shuffle(allPathObjects);
			List<PathObject> samplingPathObjects = Collections.synchronizedList(allPathObjects.subList(0, qustSetup.getNormalizationSampleSize()));

			IntStream.range(0, samplingPathObjects.size()).parallel().forEach(i -> {
//			for(int i = 0; i < samplingPathObjects.size(); i ++) {
				PathObject objObject = samplingPathObjects.get(i);
				ROI objRoi = objObject.getROI();

				int x0 = (int) (0.5 + objRoi.getCentroidX() - ((double) modelFeatureSizePixels / 2.0));
				int y0 = (int) (0.5 + objRoi.getCentroidY() - ((double) modelFeatureSizePixels / 2.0));
				RegionRequest objRegion = RegionRequest.createInstance(serverPath, 1.0, x0, y0,
						modelFeatureSizePixels, modelFeatureSizePixels);

				try {
					BufferedImage imgContent = (BufferedImage) server.readRegion(objRegion);
					BufferedImage imgBuf = new BufferedImage(imgContent.getWidth(), imgContent.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
					
					imgBuf.getGraphics().drawImage(imgContent, 0, 0, null);
					
					Path imageFilePath = Paths.get(imageSetPathString, objObject.getID().toString() + "." + imgFmt);
					
					File imageFile = new File(imageFilePath.toString());
					ImageIO.write(imgBuf, imgFmt, imageFile);
				} catch (Exception e) {
					success.set(false);
					e.printStackTrace();
				}
			});
//			}

			// Create command to run
	        VirtualEnvironmentRunner veRunner;
	        veRunner = new VirtualEnvironmentRunner(qustSetup.getEnvironmentNameOrPath(), qustSetup.getEnvironmentType(), RegionSegmentation.class.getSimpleName());
		
	        // This is the list of commands after the 'python' call
	        String script_path = Paths.get(qustSetup.getSptx2ScriptPath(), "classification.py").toString();
	        List<String> QuSTArguments = new ArrayList<>(Arrays.asList("-W", "ignore", script_path, "estimate_w", resultPathString));
	        
	        QuSTArguments.add("--image_path");
	        QuSTArguments.add(imageSetPathString);
	        veRunner.setArguments(QuSTArguments);
	        
	        // Finally, we can run the command
	        String[] logs = veRunner.runCommand();
	        for (String log : logs) logger.info(log);
			
			FileReader resultFileReader = new FileReader(new File(resultPathString));
			BufferedReader bufferedReader = new BufferedReader(resultFileReader);
			Gson gson = new Gson();
			JsonObject jsonObject = gson.fromJson(bufferedReader, JsonObject.class);
			
			Boolean ve_success = gson.fromJson(jsonObject.get("success"), new TypeToken<Boolean>(){}.getType());
			if(!ve_success) throw new Exception("classification.py returned failed");
			
			List<Double> ve_result = gson.fromJson(jsonObject.get("W"), new TypeToken<List<Double>>(){}.getType());
			
			if(ve_result == null) throw new Exception("classification.py returned null");

			W = ve_result.stream().mapToDouble(Double::doubleValue).toArray();
			
//			imageSetPath.toFile().delete();
			FileUtils.deleteDirectory(imageSetPath.toFile());
			resultPath.toFile().delete();
//			FileUtils.deleteDirectory(resultPath.toFile());
		} catch (Exception e) {
//			Dialogs.showErrorMessage("Error", e.getMessage());
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
			String modelPathStr = Paths.get(modelLocationStr, modelName+".pt").toString();
			String uuid = UUID.randomUUID().toString().replace("-", "")+hackDigit.getAndIncrement();
			Path resultPath = Files.createTempFile("QuST-classification_result-" + uuid + "-", ".json");
            String resultPathString = resultPath.toAbsolutePath().toString();
//            resultPath.toFile().deleteOnExit();
            
			// Create command to run
	        VirtualEnvironmentRunner veRunner;
			
			veRunner = new VirtualEnvironmentRunner(qustSetup.getEnvironmentNameOrPath(), qustSetup.getEnvironmentType(), ObjectClassification.class.getSimpleName());
		
	        // This is the list of commands after the 'python' call
			String script_path = Paths.get(qustSetup.getSptx2ScriptPath(), "classification.py").toString();
			
			List<String> QuSTArguments = new ArrayList<>(Arrays.asList("-W", "ignore", script_path, "param", resultPathString));
			
	        QuSTArguments.add("--model_file");
	        QuSTArguments.add("" + modelPathStr);
	        veRunner.setArguments(QuSTArguments);

	        // Finally, we can run Cellpose
	        String[] logs = veRunner.runCommand();
	        for (String log : logs) logger.info(log);
			
	        FileReader resultFileReader = new FileReader(new File(resultPathString));
			BufferedReader bufferedReader = new BufferedReader(resultFileReader);
			Gson gson = new Gson();
			JsonObject jsonObject = gson.fromJson(bufferedReader, JsonObject.class);
			
			modelPixelSizeMicrons = jsonObject.get("pixel_size").getAsDouble();
			modelNormalized = jsonObject.get("normalized").getAsBoolean();
			modelFeatureSizePixels = jsonObject.get("image_size").getAsInt();
			modelLabelList = Arrays.asList(jsonObject.get("label_list").getAsString().split(";"));
			
			PathObjectHierarchy hierarchy = imageData.getHierarchy();
			Collection<PathObject> selectedObjects = hierarchy.getSelectionModel().getSelectedObjects();
			Predicate<PathObject> pred = p -> selectedObjects.contains(p.getParent());
			
			availabelObjList = Collections.synchronizedList(QPEx.getObjects(hierarchy, pred));
			
			if(availabelObjList == null || availabelObjList.size() < qustSetup.getNormalizationSampleSize()) throw new Exception("Requires more samples for estimating H&E staining.");
			
			int maxThread = getParameterList(imageData).getIntParameterValue("maxThread");
			semaphore = maxThread > 0? new Semaphore(maxThread): null;
			
			if(modelNormalized) normalizer_w = estimate_w(imageData);
		} catch (Exception e) {
			e.printStackTrace();
			
//			Dialogs.showErrorMessage("Error", e.getMessage());
		} finally {
		    System.gc();
		}
	}	


	@Override
	public ParameterList getDefaultParameterList(ImageData<BufferedImage> imageData) {
		if (!parametersInitialized) {
			params = buildParameterList(imageData);
		}
	
		return params;
	}

	
	@Override
	public String getName() {
		return "Cell-subtype classification";
	}

	
	@Override
	public String getLastResultsDescription() {
		return detector == null ? "" : detector.getLastResultsDescription();
	}

	
	@Override
	public String getDescription() {
		return "Cell subtype classification based on machine leatning algorithms";
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
