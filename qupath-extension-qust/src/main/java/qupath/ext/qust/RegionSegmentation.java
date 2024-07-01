/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package qupath.ext.qust;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.WritableRaster;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.apache.commons.lang3.ArrayUtils;
import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import ij.IJ;
import ij.gui.Roi;
import ij.plugin.filter.ThresholdToSelection;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.StringProperty;
import qupath.imagej.tools.IJTools;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractTileableDetectionPlugin;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * Default command for cell detection within QuPath, assuming either a nuclear or cytoplasmic staining.
 * <p>
 * To automatically classify cells as positive or negative along with detection, see {@link PositiveCellDetection}.
 * <p>
 * To quantify membranous staining see {@link WatershedCellMembraneDetection}.
 * 
 * @author huangch.tw@gmail.com
 *
 */
public class RegionSegmentation extends AbstractTileableDetectionPlugin<BufferedImage> {
	
//	protected boolean parametersInitialized = false;
	private static final StringProperty qustRegsegModelNameProp = PathPrefs.createPersistentPreference("qustRegsegModelName", null);
	private static final DoubleProperty qustRegsegDetResProp = PathPrefs.createPersistentPreference("qustRegsegDetRes", 50.0);
	private static final BooleanProperty qustRegsegDetectionProp = PathPrefs.createPersistentPreference("qustRegsegDetection", true);		
	private static final DoubleProperty qustRegsegSmoothCoeffProp = PathPrefs.createPersistentPreference("qustRegsegSmoothCoeff", 0.5);
			
	private final static QuSTSetup qustSetup = QuSTSetup.getInstance();
	private final static Logger logger = LoggerFactory.getLogger(RegionSegmentation.class);
	
	private static int m_samplingFeatureSize;
	private static int m_detectionSize;
	private static int m_segmentationWidth;
	private static int m_segmentationHeight;
	private static double m_modelPreferredPixelSizeMicrons;
	private static int[] m_segmentationResult;
	private static boolean m_modelNormalized;
	private static double[] m_normalizer_w = null;
	private static String[] m_labelList;
	private static String[] m_additionalLabelList = {"not available", "error"};
	private static final AtomicInteger hackDigit = new AtomicInteger(0);
	private static final String m_imgFmt = qustSetup.getImageFileFormat().trim().charAt(0) == '.'? qustSetup.getImageFileFormat().trim().substring(1): qustSetup.getImageFileFormat().trim();
	private static final String imgFmt = qustSetup.getImageFileFormat().trim().charAt(0) == '.'? qustSetup.getImageFileFormat().trim().substring(1): qustSetup.getImageFileFormat().trim();
	
	private static Semaphore semaphore;
	ParameterList params;
	
	static class RegionSegmentationRunner implements ObjectDetector<BufferedImage> {
		private List<PathObject> pathObjects = null;
		private static final String imgFmt = qustSetup.getImageFileFormat().trim().charAt(0) == '.'? qustSetup.getImageFileFormat().trim().substring(1): qustSetup.getImageFileFormat().trim();
		
		@Override
		public Collection<PathObject> runDetection(final ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
			
			if (pathROI == null)
				throw new IOException("Region segmentation requires a ROI!");
			
			final ImageServer<BufferedImage> server = (ImageServer<BufferedImage>) imageData.getServer();
			final String serverPath = server.getPath();
			
			final List<RegionRequest> segmentationRequestList = Collections.synchronizedList(new ArrayList<RegionRequest>());
			final List<RegionRequest> availableRequestList = Collections.synchronizedList(new ArrayList<RegionRequest>());
			final List<List<Integer>> segmentXYList = Collections.synchronizedList(new ArrayList<>());
			pathObjects = Collections.synchronizedList(new ArrayList<PathObject>());
			
			Path imageSetPath = null;
            Path resultPath = null;
            
			try {
				final RegionRequest tileRegion = RegionRequest.createInstance(server.getPath(), 1.0, pathROI);

				Stream.iterate(tileRegion.getMinY(), y -> y <= tileRegion.getMaxY(), y -> m_detectionSize + y).parallel().forEach(y -> {
				// for(int y = tileRegion.getMinY(); y <= tileRegion.getMaxY(); y += m_detectionSize) {
					
					Stream.iterate(tileRegion.getMinX(), x -> x <= tileRegion.getMaxX(), x -> m_detectionSize + x).parallel().forEach(x -> {
					// for(int x = tileRegion.getMinX(); x <= tileRegion.getMaxX(); x += m_detectionSize) {
						
						final int segment_y = (int)((double)y/(double)m_detectionSize);
						final int segment_x = (int)((double)x/(double)m_detectionSize);
						
						if(segment_y < m_segmentationHeight && segment_x < m_segmentationWidth) {
							final int aligned_y = (m_detectionSize*segment_y) - (int)(0.5+((double)m_samplingFeatureSize*0.5)-((double)m_detectionSize*0.5));
							final int aligned_x = (m_detectionSize*segment_x) - (int)(0.5+((double)m_samplingFeatureSize*0.5)-((double)m_detectionSize*0.5));
							
							if(aligned_y >= 0 && aligned_y+m_samplingFeatureSize < server.getHeight() && aligned_x >= 0 && aligned_x+m_samplingFeatureSize < server.getWidth()) {
								synchronized(availableRequestList) {
									availableRequestList.add(RegionRequest.createInstance(serverPath, 1.0, aligned_x, aligned_y, m_samplingFeatureSize, m_samplingFeatureSize));
								}									
							}
						}
					});
					// }
				});
				// }
				
				final String uuid = UUID.randomUUID().toString().replace("-", "")+hackDigit.getAndIncrement()+tileRegion.getMinX()+tileRegion.getMinY();
				
				imageSetPath = Files.createTempDirectory("QuST-segmentation_imageset-" + uuid + "-");
				final String imageSetPathString = imageSetPath.toAbsolutePath().toString();
                imageSetPath.toFile().deleteOnExit();
                
                resultPath = Files.createTempFile("QuST-segmentation_result-" + uuid + "-", ".json");
                final String resultPathString = resultPath.toAbsolutePath().toString();
                resultPath.toFile().deleteOnExit();
                
                final String modelLocationStr = qustSetup.getRegsegModelLocationPath();
    			final String modelPathStr = Paths.get(modelLocationStr, params.getChoiceParameterValue("modelName")+".pt").toString();
    			
				final List<PathObject> selectedAnnotationPathObjectList = Collections.synchronizedList(
						imageData
						.getHierarchy()
						.getSelectionModel()
						.getSelectedObjects()
						.stream()
						.filter(p -> p.isAnnotation() && p.hasROI())
						.collect(Collectors.toList())
						);
				
				// Get all the represented segmentations
				final Set<PathClass> pathClasses = new HashSet<PathClass>();
				selectedAnnotationPathObjectList.forEach(r -> pathClasses.add(r.getPathClass()));
			    final PathClass[] pathClassArray = pathClasses.toArray(new PathClass[pathClasses.size()]);
			    final Map<PathClass, Color> pathClassColors = new HashMap<PathClass, Color>();			 
			    IntStream.range(0, pathClasses.size()).forEach(i -> pathClassColors.put(pathClassArray[i], new Color(i+1, i+1, i+1)));
			    
			    final AtomicBoolean success = new AtomicBoolean(true);
				
				availableRequestList.parallelStream().forEach(request-> {
//				for(var request: availableRequestList) { 
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
						    
						    selectedAnnotationPathObjectList.forEach(sltdAnnotPathObj -> {
//						    for(var sltdAnnotPathObj: selectedAnnotationPathObjectList) {
						    	final ROI sltdAnnotPathObjRoi = sltdAnnotPathObj.getROI();
						    	final Geometry sltdAnnotPathObjRoiGeom = sltdAnnotPathObjRoi.getGeometry();
						    	
						    	final ROI requestRoi = ROIs.createRectangleROI(request);
						    	final Geometry requestRoiGeom = requestRoi.getGeometry();
						    	
						    	final Geometry intersectGeom = sltdAnnotPathObjRoiGeom.intersection(requestRoiGeom);
						    	
						        if (!intersectGeom.isEmpty()) {
						        	final Shape shape = sltdAnnotPathObjRoi.getShape();
						        	final Color color = pathClassColors.get(sltdAnnotPathObj.getPathClass());
					    	        g2d.setColor(color);
					    	        g2d.fill(shape);
					    	        count.incrementAndGet();			        
						        }
						    });
//						    }
						    
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
//				}
				
				if(!success.get()) {
	        		final String message = "Region segmentation data preparation failed!.";
	        		logger.warn(message);							
					throw new IOException(message);
				}
				
				if(segmentationRequestList.size() > 0) {
					IntStream.range(0, segmentationRequestList.size()).parallel().forEachOrdered(i -> { 
					// for(var request: segmentationRequestList) {							
						final RegionRequest request = segmentationRequestList.get(i);
						
					    final int x = request.getX() + (int)(0.5+((double)m_samplingFeatureSize*0.5)-((double)m_detectionSize*0.5));
					    final int y = request.getY() + (int)(0.5+((double)m_samplingFeatureSize*0.5)-((double)m_detectionSize*0.5));
					    
				        final int segment_x = (int) ((double) x / (double) m_detectionSize);
				        final int segment_y = (int) ((double) y / (double) m_detectionSize);	
						
				        try {
				        	// Read image patches from server
							final BufferedImage readImg = (BufferedImage)server.readRegion(request);
							final BufferedImage bufImg = new BufferedImage(readImg.getWidth(), readImg.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
							bufImg.getGraphics().drawImage(readImg, 0, 0, null);
							
								//  Assign a file name by sequence
							final String imageFileName = Integer.toString(i)+"."+m_imgFmt;
							
							
							// Obtain the absolute path of the given image file name (with the predefined temporary imageset path)
							final Path imageFilePath = Paths.get(imageSetPathString, imageFileName);
							
							// Make the image file
							File imageFile = new File(imageFilePath.toString());
							ImageIO.write(bufImg, m_imgFmt, imageFile);
							
							synchronized(segmentXYList) {
								final List<Integer> xy = new ArrayList<>();
								xy.add(segment_x);
								xy.add(segment_y);
								
								segmentXYList.add(xy);
							}
				        }
				        catch (IOException e) {
							success.set(false);
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					});
					// }
					
					if(!success.get()) {
		        		final String message = "Region segmentation data preparation failed!.";
		        		logger.warn(message);							
						throw new IOException(message);
					}
					
					if(semaphore != null) semaphore.acquire();
					// Create command to run
			        VirtualEnvironmentRunner veRunner;
			        veRunner = new VirtualEnvironmentRunner(qustSetup.getEnvironmentNameOrPath(), qustSetup.getEnvironmentType(), RegionSegmentation.class.getSimpleName(), qustSetup.getSptx2ScriptPath());
				
			        // This is the list of commands after the 'python' call
			        final String script_path = Paths.get(qustSetup.getSptx2ScriptPath(), "classification.py").toString();
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
			        
			        if(m_modelNormalized) {
				        QuSTArguments.add("--normalizer_w");
				        QuSTArguments.add(String.join(" ", Arrays.stream(m_normalizer_w).boxed().map(Object::toString).collect(Collectors.toList())));
				        veRunner.setArguments(QuSTArguments);
			        }
			        
			        // Finally, we can run the command
			        final String[] logs = veRunner.runCommand();
			        for (String log : logs) logger.info(log);
			        // logger.info("Object segmentation command finished running");
					
					if(semaphore != null) semaphore.release();
					
					final FileReader resultFileReader = new FileReader(new File(resultPathString));
					final BufferedReader bufferedReader = new BufferedReader(resultFileReader);
					final Gson gson = new Gson();
					final JsonObject jsonObject = gson.fromJson(bufferedReader, JsonObject.class);
					
					final Boolean ve_success = gson.fromJson(jsonObject.get("success"), new TypeToken<Boolean>(){}.getType());
					if(!ve_success) throw new Exception("classification.py returned failed");
					
					final List<Double> ve_predicted = gson.fromJson(jsonObject.get("predicted"), new TypeToken<List<Double>>(){}.getType());
					final List<List<Double>> ve_prob_dist = gson.fromJson(jsonObject.get("probability"), new TypeToken<List<List<Double>>>(){}.getType());
					
					if(ve_predicted == null) throw new Exception("classification.py returned null");
					
					if(ve_predicted.size() != segmentationRequestList.size()) throw new Exception("classification.py returned wrong size");
					
					IntStream.range(0, ve_predicted.size()).parallel().forEach(i -> {
//					for(int ii = 0; ii < segmentationRequestList.size(); ii ++) {
//						final int i = ii;
						
						final int segment_x = segmentXYList.get(i).get(0);
						final int segment_y = segmentXYList.get(i).get(1);
						final int r = ve_predicted.get(i).intValue();
						
						m_segmentationResult[segment_y * m_segmentationWidth + segment_x] = r + m_additionalLabelList.length;
						
						if(params.getBooleanParameterValue("detection")) {
							final ROI pathRoi = ROIs.createRectangleROI(
									segmentationRequestList.get(i).getMinX()+1,
									segmentationRequestList.get(i).getMinY()+1,
									m_detectionSize-2,
									m_detectionSize-2,
									null);
							
							final PathClass pathCls = PathClass.fromString("regseg");
							
							final PathDetectionObject pathObj = (PathDetectionObject) PathObjects.createDetectionObject(pathRoi, pathCls);
							
							final MeasurementList pathObjMeasList = pathObj.getMeasurementList();
							
							IntStream.range(0, m_labelList.length).parallel().forEach(k -> {
								synchronized(pathObjMeasList) {
									pathObjMeasList.put("regseg:"+m_labelList[k], ve_prob_dist.get(i).get(k));
								}
							});
							
							pathObjMeasList.close();
							
							pathObjects.add(pathObj);
						}
						
					});
//					}
				}

				success.set(true);
		    }
		    catch (Exception e) {
		    	segmentationRequestList.parallelStream().forEach(request -> {
				    final int x = request.getX();
				    final int y = request.getY();
				    
			        final int segment_x = (int) ((double) x / (double) m_detectionSize);
			        final int segment_y = (int) ((double) y / (double) m_detectionSize);	
		    		
			        m_segmentationResult[segment_y * m_segmentationWidth + segment_x] = -1 + m_additionalLabelList.length;
		    	});
		    	
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		    finally {
		    	availableRequestList.clear();
		    	segmentationRequestList.clear();
		    	segmentXYList.clear();
				
		    	if(imageSetPath != null) imageSetPath.toFile().delete();
		    	if(resultPath != null) resultPath.toFile().delete();
				
			    System.gc();
		    }
			
			return pathObjects;
		}
		
		@Override
		public String getLastResultsDescription() {
			return "";
		}
	}
	
	
	private double[] estimate_w(ImageData<BufferedImage> imageData) {
		final ImageServer<BufferedImage> server = (ImageServer<BufferedImage>) imageData.getServer();
		final String serverPath = server.getPath();			
		final int segmentationWidth = (int)((double)(server.getWidth())/(double)m_samplingFeatureSize);
		final int segmentationHeight = (int)((double)(server.getHeight())/(double)m_samplingFeatureSize);
		double[] W = null;
		
		final String uuid = UUID.randomUUID().toString().replace("-", "");
		final AtomicBoolean success = new AtomicBoolean(true);
		
		final List<RegionRequest> segmentationRequestList = Collections.synchronizedList(new ArrayList<RegionRequest>());
		final List<RegionRequest> availableRequestList = Collections.synchronizedList(new ArrayList<RegionRequest>());
		
		try {	
			final Path resultPath = Files.createTempFile("QuST-estimate_w-result-" + uuid + "-", ".json");
	        final String resultPathString = resultPath.toAbsolutePath().toString();
	        resultPath.toFile().deleteOnExit();

	        final Path imageSetPath = Files.createTempDirectory("QuST-estimate_w-imageset-" + uuid + "-");
			final String imageSetPathString = imageSetPath.toAbsolutePath().toString();
	        imageSetPath.toFile().deleteOnExit();
        
			IntStream.range(0, segmentationHeight).parallel().forEach(y -> {
				// for(int y = 0; y < server.getHeight(); y += samplingFeatureStride) {
				IntStream.range(0, segmentationWidth).parallel().forEach(x -> {
				// for(int x = 0; x < server.getWidth(); x += samplingFeatureStride) {
					
					final int aligned_y = m_samplingFeatureSize*y;
					final int aligned_x = m_samplingFeatureSize*x;
					
					synchronized(availableRequestList) {
						availableRequestList.add(RegionRequest.createInstance(serverPath, 1.0, aligned_x, aligned_y, m_samplingFeatureSize, m_samplingFeatureSize));
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
		    
		    availableRequestList.parallelStream().forEach(request-> {	
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
		    
		    if(segmentationRequestList.size() < qustSetup.getNormalizationSampleSize()) throw new Exception("Number of available region samples is too small.");
		    
		    Collections.shuffle(segmentationRequestList);
		    final List<RegionRequest> samplingRequestList = Collections.synchronizedList(segmentationRequestList.subList(0, 1000));
		    
			if(!success.get()) throw new Exception("Estimate W data preparation failed!");
				
			IntStream.range(0, samplingRequestList.size()).parallel().forEachOrdered(i -> { 
			// for(var request: segmentationRequestList) {							
				final RegionRequest request = segmentationRequestList.get(i);
				
		        try {
		        	// Read image patches from server
					final BufferedImage readImg = (BufferedImage)server.readRegion(request);
					final BufferedImage bufImg = new BufferedImage(readImg.getWidth(), readImg.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
					bufImg.getGraphics().drawImage(readImg, 0, 0, null);
					
					//  Assign a file name by sequence
					final String imageFileName = Integer.toString(i)+"."+m_imgFmt;
					
					// Obtain the absolute path of the given image file name (with the predefined temporary imageset path)
					final Path imageFilePath = Paths.get(imageSetPathString, imageFileName);
					
					// Make the image file
					File imageFile = new File(imageFilePath.toString());
					ImageIO.write(bufImg, m_imgFmt, imageFile);
		        }
		        catch (IOException e) {
		        	success.set(false);
		        	
					e.printStackTrace();
				}
			});
			// }
			
			if(!success.get()) throw new Exception("Region segmentation data preparation failed!.");
			
			// Create command to run
	        VirtualEnvironmentRunner veRunner;
	        veRunner = new VirtualEnvironmentRunner(qustSetup.getEnvironmentNameOrPath(), qustSetup.getEnvironmentType(), RegionSegmentation.class.getSimpleName(), qustSetup.getSptx2ScriptPath());
		
	        // This is the list of commands after the 'python' call
	        final String script_path = Paths.get(qustSetup.getSptx2ScriptPath(), "classification.py").toString();
	        List<String> QuSTArguments = new ArrayList<>(Arrays.asList("-W", "ignore", script_path, "estimate_w", resultPathString));
	        
	        QuSTArguments.add("--image_path");
	        QuSTArguments.add("" + imageSetPathString);
	        veRunner.setArguments(QuSTArguments);
	        
	        QuSTArguments.add("--image_format");
	        QuSTArguments.add(imgFmt);
	        veRunner.setArguments(QuSTArguments);
	        
	        // Finally, we can run the command
	        final String[] logs = veRunner.runCommand();
	        for (String log : logs) logger.info(log);
	        // logger.info("Object segmentation command finished running");
			
			final FileReader resultFileReader = new FileReader(new File(resultPathString));
			final BufferedReader bufferedReader = new BufferedReader(resultFileReader);
			final Gson gson = new Gson();
			final JsonObject jsonObject = gson.fromJson(bufferedReader, JsonObject.class);
			
			final Boolean ve_success = gson.fromJson(jsonObject.get("success"), new TypeToken<Boolean>(){}.getType());
			if(!ve_success) throw new Exception("classification.py returned failed");
			
			final List<Double> ve_result = gson.fromJson(jsonObject.get("W"), new TypeToken<List<Double>>(){}.getType());
			if(ve_result == null) throw new Exception("classification.py returned null");
			
        	W = ve_result.stream().mapToDouble(Double::doubleValue).toArray();		        	
			
			success.set(true);
	    }
	    catch (Exception e) {
			e.printStackTrace();
			Dialogs.showErrorMessage("Error", e.getMessage());
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
		try {
			qustRegsegModelNameProp.set((String)params.getChoiceParameterValue("modelName"));
			qustRegsegDetResProp.set(params.getDoubleParameterValue("detection_resolution"));
			qustRegsegDetectionProp.set(params.getBooleanParameterValue("detection"));
			qustRegsegSmoothCoeffProp.set(params.getDoubleParameterValue("smooth_coeff"));
			
			final String modelLocationStr = qustSetup.getRegsegModelLocationPath();
			final String modelPathStr = Paths.get(modelLocationStr, params.getChoiceParameterValue("modelName")+".pt").toString();

			final String uuid = UUID.randomUUID().toString().replace("-", "");
			final Path resultPath = Files.createTempFile("QuST-segmentation_result-" + uuid + "-", ".json");
            final String resultPathString = resultPath.toAbsolutePath().toString();
            resultPath.toFile().deleteOnExit();
			
			// Create command to run
	        VirtualEnvironmentRunner veRunner;
			
			veRunner = new VirtualEnvironmentRunner(qustSetup.getEnvironmentNameOrPath(), qustSetup.getEnvironmentType(), ObjectClassification.class.getSimpleName(), qustSetup.getSptx2ScriptPath());
		
	        // This is the list of commands after the 'python' call
	        final String script_path = Paths.get(qustSetup.getSptx2ScriptPath(), "classification.py").toString();
			List<String> QuSTArguments = new ArrayList<>(Arrays.asList("-W", "ignore", script_path, "param", resultPathString));
			QuSTArguments.add("--model_file");
	        QuSTArguments.add("" + modelPathStr);
	        veRunner.setArguments(QuSTArguments);

	        // Finally, we can run Python
	        final String[] logs = veRunner.runCommand();
	        
	        for (String log : logs) logger.info(log);
	        // logger.info("Object segmentation command finished running");
			
	        final FileReader resultFileReader = new FileReader(new File(resultPathString));
			final BufferedReader bufferedReader = new BufferedReader(resultFileReader);
			final Gson gson = new Gson();
			final JsonObject jsonObject = gson.fromJson(bufferedReader, JsonObject.class);
			final int maxThread = getParameterList(imageData).getIntParameterValue("maxThread");
			final List<String> labelList = Arrays.asList(jsonObject.get("label_list").getAsString().split(";"));
			final ImageServer<BufferedImage> server = (ImageServer<BufferedImage>) imageData.getServer();	
			final double imagePixelSizeMicrons = server.getPixelCalibration().getAveragedPixelSizeMicrons();
			m_modelPreferredPixelSizeMicrons = jsonObject.get("pixel_size").getAsDouble();
			final double scalingFactor = m_modelPreferredPixelSizeMicrons / imagePixelSizeMicrons;
			final int featureSize = jsonObject.get("image_size").getAsInt();			
			final double detection_resolution = getParameterList(imageData).getDoubleParameterValue("detection_resolution");
			
			semaphore = maxThread > 0? new Semaphore(maxThread): null;
			m_labelList = labelList.toArray(new String[labelList.size()]);
			m_samplingFeatureSize = (int)(0.5+(scalingFactor*featureSize));
			m_detectionSize = (int)(0.5 + (detection_resolution / imagePixelSizeMicrons));
			m_segmentationWidth = (int)((double)server.getWidth()/(double)m_detectionSize);
			m_segmentationHeight = (int)((double)server.getHeight()/(double)m_detectionSize);
			m_segmentationResult = new int[m_segmentationHeight*m_segmentationWidth];
			m_modelNormalized = jsonObject.get("normalized").getAsBoolean();
			
			if(m_modelNormalized) m_normalizer_w = estimate_w(imageData);
		} catch (Exception e) {
			e.printStackTrace();
			Dialogs.showErrorMessage("Error", e.getMessage());
		} finally {
		    System.gc();
		}	
	}	

	@Override
	protected void postprocess(final TaskRunner taskRunner, final ImageData<BufferedImage> imageData) {
		final PathObjectHierarchy hierarchy = imageData.getHierarchy();

		final List<PathObject> selectedAnnotationPathObjectList = Collections.synchronizedList(
				imageData
				.getHierarchy()
				.getSelectionModel()
				.getSelectedObjects()
				.stream()
				.filter(p -> p.isAnnotation() && p.hasROI())
				.collect(Collectors.toList())
				);
		
		final Geometry baseGeom = GeometryTools.union(
				selectedAnnotationPathObjectList
				.stream()
				.map(e -> e.getROI().getGeometry()).collect(Collectors.toList()));
		
		final String[] labelList = ArrayUtils.addAll(m_additionalLabelList, m_labelList);
		
		IntStream.range(0, labelList.length).parallel().forEach(i -> {
			final int[] segmentationResultMask = new int[m_segmentationResult.length];
			
			IntStream.range(0, m_segmentationResult.length).parallel().forEach(j -> {
				segmentationResultMask[j] = m_segmentationResult[j] == i? 1: 0;
			});

			final BufferedImage segmentation = new BufferedImage(m_segmentationWidth, m_segmentationHeight, BufferedImage.TYPE_BYTE_GRAY);
			final WritableRaster segmentation_raster = WritableRaster.createWritableRaster(segmentation.getSampleModel(), null);
			segmentation_raster.setPixels(0, 0, m_segmentationWidth, m_segmentationHeight, segmentationResultMask);
			segmentation.setData(segmentation_raster);
			segmentation.flush();
			
			final ByteProcessor bpseg = new ByteProcessor(segmentation);
			final ByteProcessor bpresize = (ByteProcessor) bpseg.resize(m_detectionSize*m_segmentationWidth, m_detectionSize*m_segmentationHeight);
			bpresize.blurGaussian(m_detectionSize*params.getDoubleParameterValue("smooth_coeff"));
			bpresize.setThreshold(1, 1, ImageProcessor.NO_LUT_UPDATE);
		    final Roi roiIJ = new ThresholdToSelection().convert(bpresize);

		    if (roiIJ != null) {
		    	final ROI initialRoi = IJTools.convertToROI(roiIJ, 0, 0, 1, ImagePlane.getDefaultPlane());
		    	
		    	final Geometry intersectGeom = baseGeom.intersection(initialRoi.getGeometry());
		    	final ROI finalRoi = GeometryTools.geometryToROI(intersectGeom, ImagePlane.getDefaultPlane());
		    	final PathClass pathCls = PathClass.getInstance(labelList[i].strip());
			    	 
		    	final PathObject p = PathObjects.createAnnotationObject(finalRoi, pathCls);
		    	synchronized(hierarchy) {
		    		hierarchy.addObject(p);
		    		hierarchy.updateObject(p, true);
		    	}
		    }			
		});
			
		
		m_segmentationResult = null;
		m_labelList = null;
		
		System.gc();
	}

	
	private ParameterList buildParameterList(final ImageData<BufferedImage> imageData) {
		// TODO: Use a better way to determining if pixel size is available in microns

//		final AtomicBoolean success = new AtomicBoolean(false);
		ParameterList params = null;
		
		try {
			if(!imageData.getServer().getPixelCalibration().hasPixelSizeMicrons()) {
				Dialogs.showErrorMessage("Error", "Please check the image properties in left panel. Most likely the pixel size is unknown.");
				throw new Exception("No pixel size information");
			}
			
			final List<String> segmentationModeNamelList = Files.list(Paths.get(qustSetup.getRegsegModelLocationPath()))
					.filter(Files::isRegularFile)
            	    .map(p -> p.getFileName().toString())
            	    .filter(s -> s.endsWith(".pt"))
            	    .map(s -> s.replaceAll("\\.pt", ""))
            	    .collect(Collectors.toList());
			
			if(segmentationModeNamelList.size() <= 0) throw new Exception("No model exist in the model directory.");
			
			params = new ParameterList()
				.addTitleParameter("Setup parameters")
				.addChoiceParameter("modelName", "Model", qustRegsegModelNameProp.get() == null? segmentationModeNamelList.get(0): qustRegsegModelNameProp.get(), segmentationModeNamelList, "Choose the model that should be used for region segmentation")
				.addDoubleParameter("detection_resolution", "Detection Resolution (default: 25um)", qustRegsegDetResProp.get(), IJ.micronSymbol + "m", "")
				.addBooleanParameter("detection", "Save probability distribution into detections? (default: False)", qustRegsegDetectionProp.get())
				.addDoubleParameter("smooth_coeff", "Smoothing coefficient (Gaussian blurring after detection, default: 1.0)", qustRegsegSmoothCoeffProp.get(), null, "Smoothing coefficient (Gaussian blurring after detection, default: 1.0)")
				.addIntParameter("batchSize", "Batch Size in classification (default: 128)", 128, null, "Batch size in classification. The larger the faster. However, a larger batch size results larger GPU memory consumption.")		
				.addIntParameter("maxThread", "Max number of parallel threads (0: using qupath setup)", 0, null, "Max number of parallel threads (0: using qupath setup)");		
		} catch (Exception e) {						
			params = null;
			e.printStackTrace();
			Dialogs.showErrorMessage("Error", e.getMessage());
		} finally {
		    System.gc();
		}
		
		return params;
	}
	
	@Override
	protected boolean parseArgument(ImageData<BufferedImage> imageData, String arg) {		
		return super.parseArgument(imageData, arg);
	}

	@Override
	public ParameterList getDefaultParameterList(final ImageData<BufferedImage> imageData) {
		
//		if (!parametersInitialized) {
//			params = buildParameterList(imageData);
//		}
		
		if(params == null) 
			params = buildParameterList(imageData);
			
		return params;
	}

	@Override
	public String getName() {
		return "Autopath Region Segmentation";
	}

	
	@Override
	public String getLastResultsDescription() {
		return "";
	}

	@Override
	public String getDescription() {
		return "Region segmentation based on deep learning";
	}


	@Override
	protected double getPreferredPixelSizeMicrons(ImageData<BufferedImage> imageData, ParameterList params) {
		return m_modelPreferredPixelSizeMicrons;
	}


	@Override
	protected ObjectDetector<BufferedImage> createDetector(ImageData<BufferedImage> imageData, ParameterList params) {
		return new RegionSegmentationRunner();
	}


	@Override
	protected int getTileOverlap(ImageData<BufferedImage> imageData, ParameterList params) {
		return 0;
	}
		
}
