/*-
 * #%L
 * ST-AnD is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * ST-AnD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with ST-AnD.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.ext.qust;

import java.util.ArrayList;
//import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
//import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import java.awt.image.BufferedImage;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.beans.property.StringProperty;
import qupath.lib.gui.measure.ObservableMeasurementTableData;
//import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectConnections;
import qupath.lib.objects.PathRootObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractDetectionPlugin;
import qupath.lib.plugins.DetectionPluginTools;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.images.ImageData;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;
import qupath.lib.measurements.MeasurementList;

/**
 * Plugin for loading 10x Visium Annotation 
 * 
 * @author Chao Hui Huang
 *
 */
public class CellSpatialProfilingByMeasurement extends AbstractDetectionPlugin<BufferedImage> {
	
	final private static Logger logger = LoggerFactory.getLogger(CellSpatialProfilingByMeasurement.class);
	
//	private StringProperty sptAnalTgtClsProp = PathPrefs.createPersistentPreference("sptAnalTgtCls", ""); 
//	private StringProperty sptAnalOptClsProp = PathPrefs.createPersistentPreference("sptAnalOptCls", ""); 
//	private StringProperty sptAnalTypeProp = PathPrefs.createPersistentPreference("sptAnalType", "detection"); 
//	private StringProperty sptAnalIdProp = PathPrefs.createPersistentPreference("sptAnalId", "default"); 
	
//	private IntegerProperty sptAnalLayersProp = PathPrefs.createPersistentPreference("sptAnalLayer", 1000); 
	private ParameterList params;
	
	private String lastResults = null;
	private List<String> profilingTypeList = List.of("detection", "annotation");
	private List<String> conditionList = List.of(">", ">=", "<", "<=", "==", "!=");
	private int profilingType = 0;
	
	/**
	 * Constructor.
	 * @throws Exception 
	 */
	public CellSpatialProfilingByMeasurement() {
		ImageData<BufferedImage> imageData = QP.getCurrentImageData();
		PathObjectHierarchy hierarchy = imageData.getHierarchy();
		
        // Synchronizing ArrayList in Java  
        List<String> availPathClassList = Collections.synchronizedList(new ArrayList<String>());  
        List<String> selectedPathClassList = Collections.synchronizedList(new ArrayList<String>());  
        
        int sltdAnnotNum = hierarchy.getSelectionModel().getSelectedObjects().stream().filter(e -> e.isAnnotation()).collect(Collectors.toList()).size();
        int sltdDetNum = hierarchy.getSelectionModel().getSelectedObjects().stream().filter(e -> e.isDetection()).collect(Collectors.toList()).size();
        
//        if(sltdAnnotNum == 0 && sltdDetNum == 0) {
//        	throw new Exception("need either annotations or detections.");
//        }
//        else if(sltdAnnotNum == 0 && sltdDetNum > 0) {
//        	profilingType = 0;
//        }
//        else 
        if(sltdAnnotNum > 0 && sltdDetNum == 0) {
        	profilingType = 1;
        }
//        else if(sltdAnnotNum > 0 && sltdDetNum > 0) {
//        	throw new Exception("do not select both annotations and detections.");
//        }

		hierarchy.getDetectionObjects().parallelStream().forEach(d -> {
			PathClass dpthCls = profilingType == 0? d.getPathClass():
			d.getParent().isAnnotation()? d.getParent().getPathClass(): null;
					
			synchronized(availPathClassList) {
				if(dpthCls != null) { 
					if(!availPathClassList.contains(dpthCls.toString())) {
						availPathClassList.add(dpthCls.toString());
					}
				}		
			}
		});
		
		hierarchy.getSelectionModel().getSelectedObjects().parallelStream().forEach(d -> {
			PathClass dpthCls = d.getPathClass();
			
			if(dpthCls != null) {
				synchronized (availPathClassList) {  
					if(availPathClassList.contains(dpthCls.toString())) {
						availPathClassList.remove(dpthCls.toString());
					}
				}
				
				synchronized (selectedPathClassList) {  
					if(!selectedPathClassList.contains(dpthCls.toString())) {
						selectedPathClassList.add(dpthCls.toString());
					}
				}
			}
		});
		
//		String posClsList = String.join(",", selectedPathClassList);
//		String negClsList = String.join(",", availPathClassList);
		
		final ObservableMeasurementTableData measTblData = new ObservableMeasurementTableData();
		measTblData.setImageData(imageData, imageData == null ? Collections.emptyList() : hierarchy.getObjects(null, PathDetectionObject.class));
		final List<String> measNameList = measTblData.getMeasurementNames();
		
		params = new ParameterList()
			.addTitleParameter("Cell Spatial Profiling - Compute the distance to the edge of the user-defined cluster")
//			.addStringParameter("tgtCls", "Targeting Class(es)", posClsList, "Targeting Class(es)")
//			.addStringParameter("optCls", "Opponent Class(es)", negClsList, "Opponent Class(es)")
			
//			.addStringParameter("measurementName", "Measurement", sptAnalIdProp.get(), "The measurement that determine the profiling cluster.")
			.addChoiceParameter("measurementName", "Measurement", measNameList.get(measNameList.size()-1), measNameList, "The measurement that determine the profiling cluster.")
			.addChoiceParameter("targetCondition", "Condition", conditionList.get(0), conditionList, "The condition for Profiling cluster.")
			.addDoubleParameter("targetThreshold", "Value", 0.5, null, "The value that determine the profiling cluster.")
			.addChoiceParameter("type", "Profiling by", profilingTypeList.get(profilingType), profilingTypeList, "Profiling options.")
			.addStringParameter("id", "Profile ID", "untitled", "Profile ID")
			.addIntParameter("layers", "Maximal layers of detection (0 for all)", 0, null, "Maximal layers of detection")			
			;
	}
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		
		@Override
		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
		    // Set measurement-based parameters instead of class-based
		    String measurementName = (String)params.getChoiceParameterValue("measurementName");
		    double targetThreshold = params.getDoubleParameterValue("targetThreshold");
		    String targetCondition = (String)params.getChoiceParameterValue("targetCondition"); // ">=", ">", "<=", "<", "==", "!="
//		    double oppositeThreshold = params.getDoubleParameterValue("oppositeThreshold");
//		    String oppositeCondition = params.getStringParameterValue("oppositeCondition");
		    
//		    sptAnalIdProp.set(params.getStringParameterValue("id"));
//		    sptAnalLayersProp.set(params.getIntParameterValue("layers"));
		    
		    PathObjectHierarchy hierarchy = imageData.getHierarchy();
		    try {
		        PathObjectConnections connections = (PathObjectConnections) imageData.getProperty("OBJECT_CONNECTIONS");
		        if(connections == null) throw new Exception("Connections generated using Delaunay clustering are required.");
		        
		        List<PathObject> selectedAnnotationPathObjectList =
		            hierarchy.getSelectionModel().getSelectedObjects().stream()
		                .filter(e -> e.isAnnotation() && e.hasChildObjects())
		                .collect(Collectors.toList());
		        if(selectedAnnotationPathObjectList.isEmpty()) throw new Exception("Missed selected annotations");
		        
		        // Collect all detection objects
		        List<PathObject> allDetectionPathObjectList = selectedAnnotationPathObjectList.stream()
		            .flatMap(p -> p.getChildObjects().stream())
		            .filter(PathObject::isDetection)
		            .collect(Collectors.toList());
		        
		        // Pre-compute cached values
		        String measurementKey = "csp:meas:" + params.getStringParameterValue("id");
		        
		        // Option 1: Use maxLayers if specified, otherwise process all layers
		        Integer maxLayersParam = params.getIntParameterValue("layers");
		        int maxLayers = (maxLayersParam != null && maxLayersParam > 0) ? maxLayersParam : Integer.MAX_VALUE;
		        
		        // Validate that measurement exists in the dataset
		        validateMeasurementAvailability(allDetectionPathObjectList, measurementName);
		        
		        // Clear existing measurements in parallel
		        allDetectionPathObjectList.parallelStream().forEach(d -> {
		            MeasurementList dMeasList = d.getMeasurementList();
		            if(dMeasList.containsKey(measurementKey)) {
		                dMeasList.remove(measurementKey);
		            }
		        });
		        
		        // Pre-compute cell classifications based on single measurement
		        Map<PathObject, String> cellClassMap = new ConcurrentHashMap<>();
		        allDetectionPathObjectList.parallelStream().forEach(cell -> {
		            // Check target criteria first
		            if(hasMeasurementAndMeetsCriteria(cell, measurementName, targetThreshold, targetCondition)) {
		                cellClassMap.put(cell, "TARGET_CLASS");
		            } else {
		                cellClassMap.put(cell, "OPPOSITE_CLASS");
		            }
		            // Cells without measurements or not meeting either criteria are excluded
		        });
		        
		        // Create target and opposite class sets
		        Set<String> tgtClsSet = Set.of("TARGET_CLASS");
		        Set<String> optClsSet = Set.of("OPPOSITE_CLASS");
		        
		        // Filter cells that are valid targets
		        Set<PathObject> validTargetCells = cellClassMap.entrySet().stream()
		            .filter(entry -> tgtClsSet.contains(entry.getValue()))
		            .map(Map.Entry::getKey)
		            .collect(Collectors.toSet());
		        
		        if(validTargetCells.isEmpty()) {
		            logger.warn("No valid target cells found with measurement '{}' meeting condition '{} {}'", 
		                       measurementName, targetCondition, targetThreshold);
		            return new ArrayList<>(hierarchy.getRootObject().getChildObjects());
		        }
		        
		        logger.info("Found {} target cells and {} opposite cells", 
		                   validTargetCells.size(),
		                   cellClassMap.entrySet().stream()
		                       .filter(entry -> optClsSet.contains(entry.getValue()))
		                       .count());
		        
		        // BFS-based approach for spatial analysis
		        runBFSSpatialAnalysis(connections, validTargetCells, cellClassMap, tgtClsSet, optClsSet, measurementKey, maxLayers);
		        
		        hierarchy.getSelectionModel().setSelectedObject(null);
		        
		    } catch(Exception e) {
		        lastResults = e.getMessage();
		        logger.error(lastResults);
		        // Dialogs.showErrorMessage("Error", e.getMessage());
		    }
		    
		    if (Thread.currentThread().isInterrupted()) {
		        lastResults = "Interrupted!";
		        logger.warn(lastResults);
		        // Dialogs.showErrorMessage("Warning", "Interrupted!");
		    }
		    
		    return new ArrayList<>(hierarchy.getRootObject().getChildObjects());
		}

		/**
		 * Helper method to safely check if a cell has a measurement and meets criteria
		 */
		private boolean hasMeasurementAndMeetsCriteria(PathObject cell, String measurementName, 
		                                             double threshold, String condition) {
		    MeasurementList measurements = cell.getMeasurementList();
		    
		    // Test existence first - this is the key improvement
		    if(!measurements.containsKey(measurementName)) {
		        return false;
		    }
		    
		    Double value = measurements.get(measurementName);
		    return meetsCriteria(value.doubleValue(), threshold, condition);
		}

		/**
		 * Helper method for condition checking
		 */
		private boolean meetsCriteria(double value, double threshold, String condition) {
		    switch(condition) {
		        case ">=": return value >= threshold;
		        case ">": return value > threshold;
		        case "<=": return value <= threshold;
		        case "<": return value < threshold;
		        case "==": return Math.abs(value - threshold) < 1e-9; // For floating point equality
		        case "!=": return Math.abs(value - threshold) >= 1e-9; // For floating point equality
		        default: 
		            logger.warn("Unknown condition: {}. Using >= as default", condition);
		            return value >= threshold;
		    }
		}

		/**
		 * Validate that required measurement exists in the dataset
		 */
		private void validateMeasurementAvailability(List<PathObject> allDetectionPathObjectList, 
		                                           String measurementName) throws Exception {
		    Set<String> availableMeasurements = allDetectionPathObjectList.stream()
		        .flatMap(cell -> cell.getMeasurementList().keySet().stream())
		        .collect(Collectors.toSet());
		    
		    if(!availableMeasurements.contains(measurementName)) {
		        throw new Exception("Required measurement '" + measurementName + "' not found in any cells. " +
		                          "Available measurements: " + availableMeasurements);
		    }
		    
		    // Check if at least some cells have the measurement
		    long cellsWithMeasurement = allDetectionPathObjectList.stream()
		        .filter(cell -> cell.getMeasurementList().containsKey(measurementName))
		        .count();
		    
		    logger.info("Cells with measurement '{}': {}/{}", 
		               measurementName, cellsWithMeasurement, allDetectionPathObjectList.size());
		}

		/**
		 * Optimized BFS-based spatial analysis that processes cells layer by layer
		 * without redundant scanning. Time complexity: O(cells + connections)
		 * If maxLayers is Integer.MAX_VALUE, processes all reachable cells.
		 */
		private void runBFSSpatialAnalysis(PathObjectConnections connections,
		                                 Set<PathObject> validTargetCells,
		                                 Map<PathObject, String> cellClassMap,
		                                 Set<String> tgtClsSet,
		                                 Set<String> optClsSet,
		                                 String measurementKey,
		                                 int maxLayers) {
		    Set<PathObject> processedCells = ConcurrentHashMap.newKeySet();
		    
		    // Use two separate queues that we don't reassign
		    Queue<PathObject> layerA = new ConcurrentLinkedQueue<>();
		    Queue<PathObject> layerB = new ConcurrentLinkedQueue<>();
		    
		    // Initialize: Find boundary cells (target cells connected to opposite cluster)
		    validTargetCells.parallelStream().forEach(targetCell -> {
		        if(Thread.currentThread().isInterrupted()) return;
		        
		        List<PathObject> connectedCells = connections.getConnections(targetCell);
		        boolean isBoundaryCell = connectedCells.stream()
		            .anyMatch(connected -> {
		                String connectedClass = cellClassMap.get(connected);
		                return connectedClass != null && optClsSet.contains(connectedClass);
		            });
		        
		        if(isBoundaryCell) {
		            targetCell.getMeasurementList().put(measurementKey, 1.0);
		            processedCells.add(targetCell);
		            layerA.offer(targetCell);
		        }
		    });
		    
		    logger.info("Found {} boundary cells in layer 1", layerA.size());
		    
		    // BFS propagation inward - continue until no more cells or maxLayers reached
		    int layer = 1;
		    while(layer < maxLayers) {
		        final int currentLayerNum = layer;
		        
		        // Determine which queue is current and which is next based on layer parity
		        final Queue<PathObject> currentLayerQueue = (layer % 2 == 1) ? layerA : layerB;
		        final Queue<PathObject> nextLayerQueue = (layer % 2 == 1) ? layerB : layerA;
		        
		        if(currentLayerQueue.isEmpty()) {
		            logger.info("No more cells to process at layer {}. All reachable target cells have been processed.", layer + 1);
		            break; // No more cells to process - all reachable cells are done
		        }
		        
		        // Process current layer - convert to list to avoid concurrent modification
		        List<PathObject> currentLayerList = new ArrayList<>(currentLayerQueue);
		        currentLayerQueue.clear();
		        
		        currentLayerList.parallelStream().forEach(cell -> {
		            if(Thread.currentThread().isInterrupted()) return;
		            
		            List<PathObject> connectedCells = connections.getConnections(cell);
		            for(PathObject connected : connectedCells) {
		                // Check if connected cell is a valid target and not yet processed
		                if(validTargetCells.contains(connected) && !processedCells.contains(connected)) {
		                    synchronized(connected) {
		                        // Double-check after acquiring lock
		                        if(!processedCells.contains(connected)) {
		                            connected.getMeasurementList().put(measurementKey, (double)(currentLayerNum + 1));
		                            processedCells.add(connected);
		                            nextLayerQueue.offer(connected);
		                        }
		                    }
		                }
		            }
		        });
		        
		        logger.info("Layer {}: processed {} cells", currentLayerNum + 1, nextLayerQueue.size());
		        layer++;
		    }
		    
		    // Report final statistics
		    int unprocessedCells = validTargetCells.size() - processedCells.size();
		    if(unprocessedCells > 0) {
		        if(maxLayers == Integer.MAX_VALUE) {
		            logger.warn("Spatial analysis completed. {} target cells were not reachable from boundary (isolated clusters)", unprocessedCells);
		        } else {
		            logger.warn("Spatial analysis completed with layer limit {}. {} target cells were not processed (may need more layers)", maxLayers, unprocessedCells);
		        }
		    } else {
		        logger.info("Spatial analysis completed successfully. All {} target cells processed in {} layers", 
		                   processedCells.size(), layer);
		    }
		}




		

		
		
		
		
		
//		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
//		   sptAnalTgtClsProp.set(params.getStringParameterValue("tgtCls"));
//		   sptAnalOptClsProp.set(params.getStringParameterValue("optCls"));
//		   sptAnalTypeProp.set((String)params.getChoiceParameterValue("type"));
//		   sptAnalIdProp.set(params.getStringParameterValue("id"));
//		   sptAnalLayersProp.set(params.getIntParameterValue("layers"));
//		   PathObjectHierarchy hierarchy = imageData.getHierarchy();
//		   try {
//		       PathObjectConnections connections = (PathObjectConnections) imageData.getProperty("OBJECT_CONNECTIONS");
//		       if(connections == null) throw new Exception("Connections generated using Delaunay clustering are required.");
//		       List<PathObject> selectedAnnotationPathObjectList =
//		           hierarchy.getSelectionModel().getSelectedObjects().stream()
//		               .filter(e -> e.isAnnotation() && e.hasChildObjects())
//		               .collect(Collectors.toList());
//		       if(selectedAnnotationPathObjectList.isEmpty()) throw new Exception("Missed selected annotations");
//		       // Collect all detection objects
//		       List<PathObject> allDetectionPathObjectList = selectedAnnotationPathObjectList.stream()
//		           .flatMap(p -> p.getChildObjects().stream())
//		           .filter(PathObject::isDetection)
//		           .collect(Collectors.toList());
//		       // Pre-compute cached values (moved outside loops)
//		       String measurementKey = "csp:" + params.getStringParameterValue("id");
//		       boolean useDetectionType = params.getChoiceParameterValue("type").equals(profilingTypeList.get(0));
//		       int maxLayers = params.getIntParameterValue("layers");
//		       // Parse and cache class sets for O(1) lookup
//		       Set<String> tgtClsSet = Arrays.stream(params.getStringParameterValue("tgtCls").split(","))
//		           .map(String::strip)
//		           .filter(s -> !s.isEmpty())
//		           .collect(Collectors.toSet());
//		       Set<String> optClsSet = Arrays.stream(params.getStringParameterValue("optCls").split(","))
//		           .map(String::strip)
//		           .filter(s -> !s.isEmpty() && !tgtClsSet.contains(s))
//		           .collect(Collectors.toSet());
//		       // Clear existing measurements in parallel
//		       allDetectionPathObjectList.parallelStream().forEach(d -> {
//		           MeasurementList dMeasList = d.getMeasurementList();
//		           if(dMeasList.containsKey(measurementKey)) {
//		               dMeasList.remove(measurementKey);
//		           }
//		       });
//		       // Pre-compute cell classifications to avoid repeated string operations
//		       Map<PathObject, String> cellClassMap = new ConcurrentHashMap<>();
//		       allDetectionPathObjectList.parallelStream().forEach(cell -> {
//		           PathClass pathClass = useDetectionType ? cell.getPathClass() :
//		               (cell.getParent().isAnnotation() ? cell.getParent().getPathClass() : null);
//		           if(pathClass != null) {
//		               cellClassMap.put(cell, pathClass.toString().strip());
//		           }
//		       });
//		       // Filter cells that are valid targets
//		       Set<PathObject> validTargetCells = cellClassMap.entrySet().stream()
//		           .filter(entry -> tgtClsSet.contains(entry.getValue()))
//		           .map(Map.Entry::getKey)
//		           .collect(Collectors.toSet());
//		       if(validTargetCells.isEmpty()) {
//		           logger.warn("No valid target cells found");
//		           return new ArrayList<>(hierarchy.getRootObject().getChildObjects());
//		       }
//		       // BFS-based approach for much better performance
//		       runBFSSpatialAnalysis(connections, validTargetCells, cellClassMap, tgtClsSet, optClsSet, measurementKey, maxLayers);
//		       hierarchy.getSelectionModel().setSelectedObject(null);
//		   } catch(Exception e) {
//		       lastResults = e.getMessage();
//		       logger.error(lastResults);
////		       Dialogs.showErrorMessage("Error", e.getMessage());
//		   }
//		   if (Thread.currentThread().isInterrupted()) {
//		       lastResults = "Interrupted!";
//		       logger.warn(lastResults);
////		       Dialogs.showErrorMessage("Warning", "Interrupted!");
//		   }
//		   return new ArrayList<>(hierarchy.getRootObject().getChildObjects());
//		}
//		/**
//		* Optimized BFS-based spatial analysis that processes cells layer by layer
//		* without redundant scanning. Time complexity: O(cells + connections)
//		*/
//		private void runBFSSpatialAnalysis(PathObjectConnections connections,
//		                                 Set<PathObject> validTargetCells,
//		                                 Map<PathObject, String> cellClassMap,
//		                                 Set<String> tgtClsSet,
//		                                 Set<String> optClsSet,
//		                                 String measurementKey,
//		                                 int maxLayers) {
//		   Set<PathObject> processedCells = ConcurrentHashMap.newKeySet();
//		   // Use two separate queues that we don't reassign
//		   Queue<PathObject> layerA = new ConcurrentLinkedQueue<>();
//		   Queue<PathObject> layerB = new ConcurrentLinkedQueue<>();
//		   // Initialize: Find boundary cells (target cells connected to opposite cluster)
//		   validTargetCells.parallelStream().forEach(targetCell -> {
//		       if(Thread.currentThread().isInterrupted()) return;
//		       List<PathObject> connectedCells = connections.getConnections(targetCell);
//		       boolean isBoundaryCell = connectedCells.stream()
//		           .anyMatch(connected -> {
//		               String connectedClass = cellClassMap.get(connected);
//		               return connectedClass != null && optClsSet.contains(connectedClass);
//		           });
//		       if(isBoundaryCell) {
//		           targetCell.getMeasurementList().put(measurementKey, 1.0);
//		           processedCells.add(targetCell);
//		           layerA.offer(targetCell);
//		       }
//		   });
//		   // BFS propagation inward - alternate between the two queues
//		   for(int layer = 1; layer < maxLayers; layer++) {
//		       final int currentLayerNum = layer;
//		       // Determine which queue is current and which is next based on layer parity
//		       final Queue<PathObject> currentLayerQueue = (layer % 2 == 1) ? layerA : layerB;
//		       final Queue<PathObject> nextLayerQueue = (layer % 2 == 1) ? layerB : layerA;
//		       if(currentLayerQueue.isEmpty()) {
//		           break; // No more cells to process
//		       }
//		       // Process current layer - convert to list to avoid concurrent modification
//		       List<PathObject> currentLayerList = new ArrayList<>(currentLayerQueue);
//		       currentLayerQueue.clear();
//		       currentLayerList.parallelStream().forEach(cell -> {
//		           if(Thread.currentThread().isInterrupted()) return;
//		           List<PathObject> connectedCells = connections.getConnections(cell);
//		           for(PathObject connected : connectedCells) {
//		               // Check if connected cell is a valid target and not yet processed
//		               if(validTargetCells.contains(connected) && !processedCells.contains(connected)) {
//		                   synchronized(connected) {
//		                       // Double-check after acquiring lock
//		                       if(!processedCells.contains(connected)) {
//		                           connected.getMeasurementList().put(measurementKey, (double)(currentLayerNum + 1));
//		                           processedCells.add(connected);
//		                           nextLayerQueue.offer(connected);
//		                       }
//		                   }
//		               }
//		           }
//		       });
//		   }
//		}
			
//		@Override
//		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
//			sptAnalTgtClsProp.set(params.getStringParameterValue("tgtCls"));
//			sptAnalOptClsProp.set(params.getStringParameterValue("optCls"));
//			sptAnalTypeProp.set((String)params.getChoiceParameterValue("type"));
//			sptAnalIdProp.set(params.getStringParameterValue("id"));
//			sptAnalLayersProp.set(params.getIntParameterValue("layers"));
//			
//			PathObjectHierarchy hierarchy = imageData.getHierarchy();
//			
//			try {
//	            /*
//	             * Generate cell masks with their labels
//	             */
//				
//				PathObjectConnections connections = (PathObjectConnections) imageData.getProperty("OBJECT_CONNECTIONS");
//				
//				if(connections == null) throw new Exception("Connections generated using Delaunay clustering are required.");
//				
//				List<PathObject> selectedAnnotationPathObjectList = 
//					hierarchy.
//					getSelectionModel().
//					getSelectedObjects().
//					stream().
//					filter(e -> e.isAnnotation() && e.hasChildObjects()).
//					collect(Collectors.toList());
//					
//				if(selectedAnnotationPathObjectList.isEmpty()) throw new Exception("Missed selected annotations");
//
//				List<PathObject> allDetectionPathObjectList = Collections.synchronizedList(new ArrayList<>());
//				
//				selectedAnnotationPathObjectList.stream().forEach(p -> {
//					allDetectionPathObjectList.addAll(p.getChildObjects().stream().filter(e -> e.isDetection()).collect(Collectors.toList()));
//				});
//				
//				allDetectionPathObjectList.parallelStream().forEach(d -> {
//					MeasurementList dMeasList = d.getMeasurementList();
//					if(dMeasList.containsKey("csp:"+params.getStringParameterValue("id"))) {
//						dMeasList.remove("csp:"+params.getStringParameterValue("id"));
//					}						
//				});
//				
//				List<String> tgtClsLst = 
//						Arrays.stream(params.getStringParameterValue("tgtCls").
//						split(",")).
//						map(s -> s.strip()).
//						filter(e -> !e.isEmpty()).
//						collect(Collectors.toList());
//				
//				List<String> optClsLst = 
//						Arrays.stream(params.getStringParameterValue("optCls").
//						split(",")).
//						map(s -> s.strip()).
//						filter(e -> !tgtClsLst.contains(e) && !e.isEmpty()).
//						collect(Collectors.toList());
//				
//				for(int l = 0; l < params.getIntParameterValue("layers"); l ++) {
//					int layer = l;
//					AtomicBoolean terminate_flag = new AtomicBoolean(true);
//
////					allDetectionPathObjectList.parallelStream().forEach(c -> {
//					for(PathObject c: allDetectionPathObjectList) {
//						if(c.getMeasurementList().containsKey("csp:"+params.getStringParameterValue("id"))) 
////							return;
//							continue;
//						
//						PathClass cPthCls = ((String)params.getChoiceParameterValue("type")).equals(profilingTypeList.get(0))? c.getPathClass(): 
//							c.getParent().isAnnotation()? c.getParent().getPathClass(): null;
//						
//						if(cPthCls == null) 
////							return;
//							continue;
//						
//						String cCls = cPthCls.toString().strip();
//						
//						if(tgtClsLst.stream().anyMatch(cCls::equals)) {
//							List<PathObject> connectedObj = connections.getConnections(c);
//							
//							for(PathObject d: connectedObj) {
//								PathClass dPthCls = ((String)params.getChoiceParameterValue("type")).equals(profilingTypeList.get(0))? d.getPathClass():
//									d.getParent().isAnnotation()? d.getParent().getPathClass(): null;
//								
//								if(dPthCls == null) continue;
//								
//								if(layer == 0) {
//									String dCls = dPthCls.toString().strip();
//									
//									if(optClsLst.stream().anyMatch(dCls::equals)) {
//										MeasurementList tgtObjMeasList = c.getMeasurementList();
//										tgtObjMeasList.put("csp:"+params.getStringParameterValue("id"), layer+1);
//										
//										terminate_flag.set(false);
//										break;
//									}
//								}
//								else {
//									MeasurementList optObjMeasList = d.getMeasurementList();
//									Double v = optObjMeasList.get("csp:"+params.getStringParameterValue("id"));
//									
//									if((!v.isNaN()) && (v.intValue() == layer)) {
//										MeasurementList tgtObjMeasList = c.getMeasurementList();
//										tgtObjMeasList.put("csp:"+params.getStringParameterValue("id"), layer+1);
//										
//										terminate_flag.set(false);
//										break;
//									}	
//								}
//							}
//						}		
////					});
//					}
//					
//					if(terminate_flag.get()) break;
//				}
//				
//		        hierarchy.getSelectionModel().setSelectedObject(null);
//			}
//			catch(Exception e) {
//				lastResults = e.getMessage();
//				logger.error(lastResults);
//				Dialogs.showErrorMessage("Error", e.getMessage());
//			}				
//			
//			if (Thread.currentThread().isInterrupted()) {
//				lastResults =  "Interrupted!";
//				logger.warn(lastResults);
//				Dialogs.showErrorMessage("Warning", "Interrupted!");
//			}
//			
//			return new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
//		}
		
		@Override
		public String getLastResultsDescription() {
			return lastResults;
		}
	}

	@Override
	public ParameterList getDefaultParameterList(ImageData<BufferedImage> imageData) {
		return params;
	}

	@Override
	public String getName() {
		return "Cell Spatial Profiling";
	}

	@Override
	public String getLastResultsDescription() {
		return lastResults;
	}

	@Override
	public String getDescription() {
		return "Compute the distance to the edge of a user defined cluster";
	}

	@Override
	protected void addRunnableTasks(ImageData<BufferedImage> imageData, PathObject parentObject, List<Runnable> tasks) {
		tasks.add(DetectionPluginTools.createRunnableTask(new AnnotationLoader(), getParameterList(imageData), imageData, parentObject));
	}


	@Override
	protected Collection<? extends PathObject> getParentObjects(ImageData<BufferedImage> imageData) {	
		PathObjectHierarchy hierarchy = imageData.getHierarchy();
		if (hierarchy.getTMAGrid() == null)
			return Collections.singleton(hierarchy.getRootObject());
		
		return hierarchy.getSelectionModel().getSelectedObjects().stream().filter(p -> p.isTMACore()).collect(Collectors.toList());
	}

	@Override
	public Collection<Class<? extends PathObject>> getSupportedParentObjectClasses() {
		// Temporarily disabled so as to avoid asking annoying questions when run repeatedly
		List<Class<? extends PathObject>> list = new ArrayList<>();
//		list.add(TMACoreObject.class);
		list.add(PathAnnotationObject.class);
		list.add(PathRootObject.class);
		return list;		

//		return Arrays.asList(
//				PathAnnotationObject.class,
//				TMACoreObject.class
//				);
	}
}
