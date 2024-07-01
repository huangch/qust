/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2023 QuPath developers, The University of Edinburgh
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


import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.imageio.ImageIO;

import org.apache.commons.lang3.ArrayUtils;
import org.imgscalr.Scalr;
import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.StringProperty;
import qupath.ext.qust.ObjectClassificationImageAcquisition.DetectedObjectImageSampling;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractInteractivePlugin;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * Experimental plugin to help with the quantification of stardist cell nuclei structures.
 * 
 * @author Chao Hui Huang
 *
 */
public class RegionSegmentationImageAcquisition extends AbstractInteractivePlugin<BufferedImage> {
	protected static QuSTSetup qustSetup = QuSTSetup.getInstance();
	
	private static final Logger logger = LoggerFactory.getLogger(RegionSegmentationImageAcquisition.class);
	private static String lastResults = null;
	
	private static final StringProperty regSegImgAcqDistDirProp = PathPrefs.createPersistentPreference("regSegImgAcqDistDir", "");
//	private static final BooleanProperty regSegImgAcqDontRescalingProp = PathPrefs.createPersistentPreference("regSegImgAcqDontRescaling", true);
//	private static final BooleanProperty regSegImgAcqNormalizationProp = PathPrefs.createPersistentPreference("regSegImgAcqNormalization", true);
	private static final DoubleProperty regSegImgAcqMppProp = PathPrefs.createPersistentPreference("regSegImgAcqMpp",0.21233);
	private static final IntegerProperty regSegImgAcqSamplingSizeProp = PathPrefs.createPersistentPreference("regSegImgAcqSamplingSize", 224);
	private static final IntegerProperty regSegImgAcqSamplingStrideProp = PathPrefs.createPersistentPreference("regSegImgAcqSamplingStride", 112);
	private static final BooleanProperty regSegImgAcqAllSamplesProp = PathPrefs.createPersistentPreference("regSegImgAcqAllSamplesProp", true);
	private static final IntegerProperty regSegImgAcqSamplingNumProp = PathPrefs.createPersistentPreference("regSegImgAcqSamplingNum", 0);
//	private static final StringProperty regSegImgAcqSamplingFmtProp = PathPrefs.createPersistentPreference("regSegImgAcqSamplingFmt", "png");
	
	private static ParameterList params;
	
	/**
	 * Default constructor.
	 */
	public RegionSegmentationImageAcquisition() {
		params = new ParameterList()
			.addStringParameter("distFolder", "Distination Folder", regSegImgAcqDistDirProp.get(), "Distination Folder")
			.addEmptyParameter("").addEmptyParameter("Reampling using...")
			.addBooleanParameter("normalization", "Normalization (default: yes)", true, "Normalization (default: no)")
			.addBooleanParameter("dontResampling", "Do not rescaling image (default: yes)", true, "Do not rescaling image (default: yes)")
			.addEmptyParameter("...or...")
			.addDoubleParameter("mpp", "pixel size", regSegImgAcqMppProp.get(), GeneralTools.micrometerSymbol(), "Pixel Size")
			.addEmptyParameter("")
			.addIntParameter("samplingSize", "Sampling Size", regSegImgAcqSamplingSizeProp.get(), "pixel(s)", "Sampling Size")
			.addIntParameter("samplingStride", "Sampling Stride", regSegImgAcqSamplingStrideProp.get(), "pixel(s)", "Sampling Stride")
			.addBooleanParameter("allSamples", "Include all samples (default: yes)", regSegImgAcqAllSamplesProp.get(), "Include all samples? (default: yes)")
			.addEmptyParameter("...or...")
			.addIntParameter("samplingNum", "Maximal Sampling Number", regSegImgAcqSamplingNumProp.get(), "objects(s)", "Maximal Sampling Number")
//			.addStringParameter("format", "Image File Format (e.g., png, tiff, etc.) ", qustSetup.getImageFileFormat(), "Image File Format")
			;
	}

	
	@Override
	public boolean runPlugin(final TaskRunner taskRunner, final ImageData<BufferedImage> imageData, final String arg) {
		boolean success = super.runPlugin(taskRunner, imageData, arg);
		imageData.getHierarchy().fireHierarchyChangedEvent(this);
		return success;
	}
	
	
	@Override
	protected void addRunnableTasks(final ImageData<BufferedImage> imageData, final PathObject parentObject, List<Runnable> tasks) {
		final ParameterList params = getParameterList(imageData);
		
		tasks.add(new RegionSegmentationRunnable(imageData, parentObject, params));
	}
	
	
	static class RegionSegmentationRunnable implements Runnable {
		private ImageData<BufferedImage> imageData;
		private ParameterList params;
		private PathObject parentObject;
		
		public RegionSegmentationRunnable(final ImageData<BufferedImage> imageData, final PathObject parentObject, final ParameterList params) {
			this.imageData = imageData;
			this.parentObject = parentObject;
			this.params = params;
		}

		@Override
		public void run() {
			try {
				if (parentObject instanceof PathAnnotationObject) {
//					regSegImgAcqDontRescalingProp.set(params.getBooleanParameterValue("dontResampling"));
//					regSegImgAcqNormalizationProp.set(params.getBooleanParameterValue("normalization"));
					regSegImgAcqMppProp.set(params.getDoubleParameterValue("mpp"));
					regSegImgAcqSamplingSizeProp.set(params.getIntParameterValue("samplingSize"));
					regSegImgAcqSamplingStrideProp.set(params.getIntParameterValue("samplingStride"));
					regSegImgAcqAllSamplesProp.set(params.getBooleanParameterValue("allSamples"));
					regSegImgAcqSamplingNumProp.set(params.getIntParameterValue("samplingNum"));
//					regSegImgAcqSamplingFmtProp.set(params.getStringParameterValue("format"));
					
					final ImageServer<BufferedImage> server = (ImageServer<BufferedImage>) imageData.getServer();
					final String serverPath = server.getPath();
					
					final double imageMpp = server.getPixelCalibration().getAveragedPixelSizeMicrons();
					final double scalingFactor = params.getBooleanParameterValue("dontResampling") ? 1.0: regSegImgAcqMppProp.get() / imageMpp;
					final int featureSize = (int)(0.5 + scalingFactor * regSegImgAcqSamplingSizeProp.get());
					final int stride = (int)(0.5 + scalingFactor * regSegImgAcqSamplingStrideProp.get());
					
					final int segmentationWidth = 1+(int)((double)(server.getWidth()-featureSize)/(double)stride);
					final int segmentationHeight = 1+(int)((double)(server.getHeight()-featureSize)/(double)stride);
					
					final List<RegionRequest> allRegionList = Collections.synchronizedList(new ArrayList<RegionRequest>());
					final List<RegionRequest> availRegionList = Collections.synchronizedList(new ArrayList<RegionRequest>());

					try {
						IntStream.range(0, segmentationHeight).parallel().forEach(y -> {
			//			for(int y = 0; y < segmentationHeight; y ++) {
							IntStream.range(0, segmentationWidth).parallel().forEach(x -> {
			//				for(int x = 0; x < segmentationWidth; x ++) {
								final int alignedY = stride*y;
								final int alignedX = stride*x;
								
								synchronized(allRegionList) {
									allRegionList.add(RegionRequest.createInstance(serverPath, 1.0, alignedX, alignedY, featureSize, featureSize));
								}
							});
			//				}
						});
			//			}
			
						final AtomicBoolean success = new AtomicBoolean(true);
						
						final ROI annotObjRoi = parentObject.getROI();
						final Geometry annotObjRoiGeom = annotObjRoi.getGeometry();
						
						allRegionList.parallelStream().forEach(r -> {
							if(success.get()) {
								final ROI regionRoi = ROIs.createRectangleROI(r);
								final Geometry intersect = annotObjRoiGeom.intersection(regionRoi.getGeometry());
								if(!intersect.isEmpty()) {
									try {	
										synchronized(availRegionList) {
											availRegionList.add(r);
										}
									}
							        catch (Exception e) {
							        	success.set(false);
										// TODO Auto-generated catch block
										e.printStackTrace();
									}
								}
							}
						});
						
						assert success.get(): "Region segmentation data preparation failed!";
						
						final int samplingNum = regSegImgAcqAllSamplesProp.get()? availRegionList.size(): regSegImgAcqSamplingNumProp.get() <= availRegionList.size()? regSegImgAcqSamplingNumProp.get(): availRegionList.size();
							
						availRegionList.subList(0, samplingNum).parallelStream().forEach(r -> {
							try {	
								final BufferedImage imgContent = (BufferedImage) server.readRegion(r);
								final BufferedImage imgBuffer = new BufferedImage(imgContent.getWidth(), imgContent.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
								imgBuffer.getGraphics().drawImage(imgContent, 0, 0, null);
								final BufferedImage imgSampled = params.getBooleanParameterValue("dontResampling") ? imgBuffer: Scalr.resize(imgBuffer, regSegImgAcqSamplingSizeProp.get());
								final String filename = parentObject.getID().toString()+"."+Integer.toString(r.getX())+"-"+Integer.toString(r.getY());
								final String fileExt = qustSetup.getImageFileFormat().strip().charAt(0) == '.' ? qustSetup.getImageFileFormat().substring(1) : qustSetup.getImageFileFormat();
								final Path imageFilePath = Paths.get(Paths.get(regSegImgAcqDistDirProp.get()).toString(), filename + "." + fileExt);
								final File imageFile = new File(imageFilePath.toString());
								ImageIO.write(imgSampled, fileExt, imageFile);
							}
					        catch (Exception e) {
					        	success.set(false);
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
						});
						
						assert success.get(): "Region segmentation data preparation failed!";
					}
				    catch (Exception e) {
						// TODO Auto-generated catch block
				    	logger.warn(e.toString());
						e.printStackTrace();
					}
				    finally {
				    	allRegionList.clear();
				    	availRegionList.clear();
				    	
					    System.gc();
				    }
				}
				else {
					throw new IOException("The chosen object is not annotations.");
				}
			} catch (IOException e) {
				logger.error("Error processing " + parentObject, e);
			} finally {
				parentObject.getMeasurementList().close();
				imageData = null;
				params = null;
			}
		}
		
		
		@Override
		public String toString() {
			// TODO: Give a better toString()
			return "stardist cell nuclei detection";
		}
	}

	
	private double[] estimate_w(ImageData<BufferedImage> imageData) {
		final ImageServer<BufferedImage> server = (ImageServer<BufferedImage>) imageData.getServer();
		final String serverPath = server.getPath();			
		final int segmentationWidth = (int)((double)(server.getWidth())/(double)regSegImgAcqSamplingSizeProp.get());
		final int segmentationHeight = (int)((double)(server.getHeight())/(double)regSegImgAcqSamplingSizeProp.get());
		double[] W = null;
		
//		final String timeStamp = Long.toString(System.nanoTime());
		final String uuid = UUID.randomUUID().toString().replace("-", "");
		
		final AtomicBoolean success = new AtomicBoolean(true);
		
		final List<RegionRequest> segmentationRequestList = Collections.synchronizedList(new ArrayList<RegionRequest>());
		final List<RegionRequest> availableRequestList = Collections.synchronizedList(new ArrayList<RegionRequest>());
		
		try {	
			final Path resultPath = Files.createTempFile("qust-estimate_w-result-" + uuid + "-", ".json");
	        final String resultPathString = resultPath.toAbsolutePath().toString();
	        resultPath.toFile().deleteOnExit();

	        final Path imageSetPath = Files.createTempDirectory("qust-estimate_w-imageset-" + uuid + "-");
			final String imageSetPathString = imageSetPath.toAbsolutePath().toString();
	        imageSetPath.toFile().deleteOnExit();
        
			IntStream.range(0, segmentationHeight).parallel().forEach(y -> {
				// for(int y = 0; y < server.getHeight(); y += samplingFeatureStride) {
				IntStream.range(0, segmentationWidth).parallel().forEach(x -> {
				// for(int x = 0; x < server.getWidth(); x += samplingFeatureStride) {
					
					final int aligned_y = regSegImgAcqSamplingSizeProp.get()*y;
					final int aligned_x = regSegImgAcqSamplingSizeProp.get()*x;
					
					synchronized(availableRequestList) {
						availableRequestList.add(RegionRequest.createInstance(serverPath, 1.0, aligned_x, aligned_y, regSegImgAcqSamplingSizeProp.get(), regSegImgAcqSamplingSizeProp.get()));
					}
				});
				// }
			});
			// }
			
			final PathObjectHierarchy hierarchy = imageData.getHierarchy();
			final List<PathObject> RoiRegions = hierarchy.getFlattenedObjectList(null).stream()
		    		.filter(p->p.isAnnotation() && p.hasROI())
		    		.collect(Collectors.toList());
		 
		    // Get all the represented classifications
			final Set<PathClass> pathClasses = new HashSet<PathClass>();
		    RoiRegions.forEach(r -> pathClasses.add(r.getPathClass()));
		    final PathClass[] pathClassArray = pathClasses.toArray(new PathClass[pathClasses.size()]);
		    final Map<PathClass, Color> pathClassColors = new HashMap<PathClass, Color>();			 
		    IntStream.range(1, pathClasses.size()).forEach(i -> pathClassColors.put(pathClassArray[i], new Color(i, i, i)));
		    
		    assert availableRequestList.size() > 1000: "Number of available region samples is too small."; 
		    
		    Collections.shuffle(availableRequestList);
		    final List<RegionRequest> samplingRequestList = Collections.synchronizedList(availableRequestList.subList(0, 1000));
		    
		    samplingRequestList.parallelStream().forEach(request-> {	
			// for(var request: availableRequestList) { 
				if(success.get()) {
					try {				    		
			    		final BufferedImage img = (BufferedImage)server.readRegion(request);
			    				    		
			    		final int width = img.getWidth();
			    		final int height = img.getHeight();
			    		final int x = request.getX();
			    		final int y = request.getY();
	
					    // Fill the tissues with the appropriate label
			    		final BufferedImage imgMask = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
			    		final Graphics2D g2d = imgMask.createGraphics();
					    g2d.setClip(0, 0, width, height);
					    g2d.scale(1.0, 1.0);
					    g2d.translate(-x, -y);
				
					    final AtomicInteger count = new AtomicInteger(0);
					    RoiRegions.forEach(roi_region -> {
					    // for(var roi_region: RoiRegions) {
					    	final ROI roi = roi_region.getROI();
					        if (request.intersects(roi.getBoundsX(), roi.getBoundsY(), roi.getBoundsWidth(), roi.getBoundsHeight())) {
					        	final Shape shape = roi.getShape();
					        	final Color color = pathClassColors.get(roi_region.getPathClass());
				    	        g2d.setColor(color);
				    	        g2d.fill(shape);
				    	        count.incrementAndGet();			        
					        }
					    });
					    // }
					    
					    g2d.dispose();
					    
					    if (count.get() > 0) {
					        // Extract the bytes from the image
					    	final DataBufferByte buf = (DataBufferByte)imgMask.getRaster().getDataBuffer();
					    	final byte[] bytes = buf.getData();
					    	
					    	final List<Byte> byteList = Arrays.asList(ArrayUtils.toObject(bytes));
					        // Check if we actually have any non-zero pixels, if necessary -
					        // we might not if the tissue bounding box intersected the region, but the tissue itself does not
					    	
					    	if(byteList.stream().filter(b -> b != 0).count() > 0) {
					    		synchronized(segmentationRequestList) {
					    			segmentationRequestList.add(request);
					    		}
					        }
					    }	
					} catch (IOException e) {
						// TODO Auto-generated catch block
						success.set(false);
						e.printStackTrace();
					}	
	    		}
			});
			
			assert success.get(): "Estimate W data preparation failed!";
							
			IntStream.range(0, segmentationRequestList.size()).parallel().forEachOrdered(i -> { 
			// for(var request: segmentationRequestList) {							
				final RegionRequest request = segmentationRequestList.get(i);
				
		        try {
		        	// Read image patches from server
					final BufferedImage readImg = (BufferedImage)server.readRegion(request);
					final BufferedImage bufImg = new BufferedImage(readImg.getWidth(), readImg.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
					bufImg.getGraphics().drawImage(readImg, 0, 0, null);
					
					//  Assign a file name by sequence
					final String imageFileName = Integer.toString(i)+"."+qustSetup.getImageFileFormat();
					
					// Obtain the absolute path of the given image file name (with the predefined temporary imageset path)
					final Path imageFilePath = Paths.get(imageSetPathString, imageFileName);
					
					// Make the image file
					File imageFile = new File(imageFilePath.toString());
					ImageIO.write(bufImg, qustSetup.getImageFileFormat(), imageFile);
		        }
		        catch (IOException e) {
		        	success.set(false);
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			});
			// }
			
			assert success.get(): "Region segmentation data preparation failed!.";
	    	
			// Create command to run
	        VirtualEnvironmentRunner veRunner;
	        veRunner = new VirtualEnvironmentRunner(qustSetup.getEnvironmentNameOrPath(), qustSetup.getEnvironmentType(), RegionSegmentation.class.getSimpleName(), qustSetup.getSptx2ScriptPath());
		
	        // This is the list of commands after the 'python' call
	        final String script_path = Paths.get(qustSetup.getSptx2ScriptPath(), "classification.py").toString();
	        List<String> qustArguments = new ArrayList<>(Arrays.asList("-W", "ignore", script_path, "estimate_w", resultPathString));
	        
	        qustArguments.add("--image_path");
	        qustArguments.add(imageSetPathString.trim().contains(" ")? "\""+imageSetPathString.trim()+"\"": imageSetPathString.trim());
	        veRunner.setArguments(qustArguments);
	        
	        // Finally, we can run the command
	        final String[] logs = veRunner.runCommand();
	        for (String log : logs) logger.info(log);
	        // logger.info("Object segmentation command finished running");
			
			final FileReader resultFileReader = new FileReader(new File(resultPathString));
			final BufferedReader bufferedReader = new BufferedReader(resultFileReader);
			final Gson gson = new Gson();
			final JsonObject jsonObject = gson.fromJson(bufferedReader, JsonObject.class);
			
			final Boolean ve_success = gson.fromJson(jsonObject.get("success"), new TypeToken<Boolean>(){}.getType());
			assert ve_success: "classification.py returned failed";
			
			final List<Double> ve_result = gson.fromJson(jsonObject.get("W"), new TypeToken<List<Double>>(){}.getType());
			
			assert ve_result != null: "classification.py returned null";

        	W = ve_result.stream().mapToDouble(Double::doubleValue).toArray();		        	
			
			success.set(true);
	    }
	    catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	    finally {
	    	availableRequestList.clear();
	    	segmentationRequestList.clear();
	    	
		    System.gc();
	    }
		
		return W;
	}
	
	
	@Override
	protected void preprocess(final TaskRunner taskRunner, final ImageData<BufferedImage> imageData) {
		if (params.getStringParameterValue("distFolder").isBlank()) {
			final File regSegImgAcqDistDirFp = Dialogs.promptForDirectory("Output directory", new File(regSegImgAcqDistDirProp.get()));

			if (regSegImgAcqDistDirFp != null) {
				regSegImgAcqDistDirProp.set(regSegImgAcqDistDirFp.toString());
			} else {
				Dialogs.showErrorMessage("Warning", "No output directory is selected!");
				lastResults = "No output directory is selected!";
				logger.warn(lastResults);
			}
		} else {
			regSegImgAcqDistDirProp.set(params.getStringParameterValue("distFolder"));
		}
		
	}

	
	@Override
	protected void postprocess(final TaskRunner taskRunner, final ImageData<BufferedImage> imageData) {
		if(getParameterList(imageData).getBooleanParameterValue("normalization")) {		
			try {
//				final String timeStamp = Long.toString(System.nanoTime());
				final String uuid = UUID.randomUUID().toString().replace("-", "");
				final Path resultPath = Files.createTempFile("qust-normalization_result-" + uuid + "-", ".json");
		        final String resultPathString = resultPath.toAbsolutePath().toString();
		        resultPath.toFile().deleteOnExit();
		        
				// Create command to run
		        VirtualEnvironmentRunner veRunner;
		        veRunner = new VirtualEnvironmentRunner(qustSetup.getEnvironmentNameOrPath(), qustSetup.getEnvironmentType(), ObjectClassificationImageAcquisition.class.getSimpleName(), qustSetup.getSptx2ScriptPath());
			
		        // This is the list of commands after the 'python' call
		        // List<String> QuSTArguments = new ArrayList<>(Arrays.asList("-W", "ignore", "-m", "/workspace/QuST/qupath-QuST/qupath-extension-QuST/scripts/object_classifier"));
				final String script_path = Paths.get(qustSetup.getSptx2ScriptPath(), "classification.py").toString();
				
				// List<String> QuSTArguments = new ArrayList<>(Arrays.asList("-W", "ignore", "/workspace/QuST/qupath-QuST/qupath-extension-QuST/scripts/classification.py", "param", resultPathString));
				List<String> qustArguments = new ArrayList<>(Arrays.asList("-W", "ignore", script_path, "normalize", resultPathString));
				
		        qustArguments.add("--image_path");
		        qustArguments.add(regSegImgAcqDistDirProp.get().trim().contains(" ")? "\""+regSegImgAcqDistDirProp.get().trim()+"\"": regSegImgAcqDistDirProp.get().trim());
		        		
		        veRunner.setArguments(qustArguments);
		
		        // Finally, we can run Cellpose
		        final String[] logs = veRunner.runCommand();
		        // veRunner.runCommand();
		        
		        for (String log : logs) logger.info(log);
		        // logger.info("Object classification command finished running");
		        
		        final FileReader resultFileReader = new FileReader(new File(resultPathString));
				final BufferedReader bufferedReader = new BufferedReader(resultFileReader);
				final Gson gson = new Gson();
				final JsonObject jsonObject = gson.fromJson(bufferedReader, JsonObject.class);
		        final Boolean ve_success = gson.fromJson(jsonObject.get("success"), new TypeToken<Boolean>(){}.getType());
		        resultPath.toFile().delete();
		        
		        assert ve_success: "classification.py returned failed for normalizatrion";
			} catch (Exception e) {
				Dialogs.showErrorMessage("Error", e.getMessage());
				e.printStackTrace();
			} finally {
				System.gc();
			}
		}		
	}
	
	
	@Override
	public ParameterList getDefaultParameterList(final ImageData<BufferedImage> imageData) {
		return params;
	}

	
	@Override
	public String getName() {
		return "stardist cell nucleus detection";
	}

	
	@Override
	public String getLastResultsDescription() {
		return "";
	}

	
	@Override
	public String getDescription() {
		return "Run stardist cell nucleus detection";
	}

	
	@Override
	protected Collection<PathObject> getParentObjects(final ImageData<BufferedImage> imageData) {
		Collection<Class<? extends PathObject>> parentClasses = getSupportedParentObjectClasses();
		List<PathObject> parents = new ArrayList<>();
		for (PathObject parent : imageData.getHierarchy().getSelectionModel().getSelectedObjects()) {
			for (Class<? extends PathObject> cls : parentClasses) {
				if (cls.isAssignableFrom(parent.getClass())) {
					parents.add(parent);
					break;
				}
			}
		}
		
		return parents;
	}

	
	@Override
	public Collection<Class<? extends PathObject>> getSupportedParentObjectClasses() {
		List<Class<? extends PathObject>> parents = new ArrayList<>();
		parents.add(TMACoreObject.class);
		parents.add(PathAnnotationObject.class);

		return parents;
	}
	

}
