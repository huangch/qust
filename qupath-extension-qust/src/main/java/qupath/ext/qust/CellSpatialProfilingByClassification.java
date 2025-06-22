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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

import java.awt.image.BufferedImage;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.beans.property.StringProperty;
import javafx.beans.property.IntegerProperty;

//import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.objects.PathAnnotationObject;
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
public class CellSpatialProfilingByClassification extends AbstractDetectionPlugin<BufferedImage> {
	
	final private static Logger logger = LoggerFactory.getLogger(CellSpatialProfilingByClassification.class);
	
//	private StringProperty sptAnalTgtClsProp = PathPrefs.createPersistentPreference("sptAnalTgtCls", ""); 
//	private StringProperty sptAnalOptClsProp = PathPrefs.createPersistentPreference("sptAnalOptCls", ""); 
//	private StringProperty sptAnalTypeProp = PathPrefs.createPersistentPreference("sptAnalType", "detection"); 
//	private StringProperty sptAnalIdProp = PathPrefs.createPersistentPreference("sptAnalId", "default"); 
	
//	private IntegerProperty sptAnalLayersProp = PathPrefs.createPersistentPreference("sptAnalLayer", 1000); 
	private ParameterList params;
	
	private String lastResults = null;
	private List<String> profilingTypeList = List.of("detection", "annotation");
	private int profilingType = 0;
	
	/**
	 * Constructor.
	 * @throws Exception 
	 */
	public CellSpatialProfilingByClassification() {
		PathObjectHierarchy hierarchy = QP.getCurrentImageData().getHierarchy();
		
        // Synchronizing ArrayList in Java  
        List<String> availPathClassList = Collections.synchronizedList(new ArrayList<String>());  
        List<String> selectedPathClassList = Collections.synchronizedList(new ArrayList<String>());  
        
        int sltdAnnotNum = hierarchy.getSelectionModel().getSelectedObjects().stream().filter(e -> e.isAnnotation()).collect(Collectors.toList()).size();
        int sltdDetNum = hierarchy.getSelectionModel().getSelectedObjects().stream().filter(e -> e.isDetection()).collect(Collectors.toList()).size();
        

        if(sltdAnnotNum > 0 && sltdDetNum == 0) {
        	profilingType = 1;
        }


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
		
		String posClsList = String.join(",", selectedPathClassList);
//		String negClsList = String.join(",", availPathClassList);
		
		params = new ParameterList()
			.addTitleParameter("Cell Spatial Profiling - Compute the distance to the edge of the user-defined cluster")
			.addStringParameter("tgtCls", "Targeting Class(es)", posClsList, "Targeting Class(es)")
//			.addStringParameter("optCls", "Opponent Class(es)", negClsList, "Opponent Class(es)")
			.addChoiceParameter("type", "Profiling by", profilingTypeList.get(profilingType), profilingTypeList, "Profiling options")
			.addStringParameter("id", "Profile ID", "untitled", "Profile ID")
			.addIntParameter("layers", "Maximal layers of detection (0 for all)", 0, null, "Maximal layers of detection")			
			;
	}
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		
		@Override
		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
//		   sptAnalTgtClsProp.set(params.getStringParameterValue("tgtCls"));
//		   sptAnalOptClsProp.set(params.getStringParameterValue("optCls"));
//		   sptAnalTypeProp.set((String)params.getChoiceParameterValue("type"));
//		   sptAnalIdProp.set(params.getStringParameterValue("id"));
//		   sptAnalLayersProp.set(params.getIntParameterValue("layers"));
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
		       // Pre-compute cached values (moved outside loops)
		       String measurementKey = "csp:cls:" + params.getStringParameterValue("id");
		       boolean useDetectionType = params.getChoiceParameterValue("type").equals(profilingTypeList.get(0));
		       Integer maxLayersParam = params.getIntParameterValue("layers");
		       int maxLayers = (maxLayersParam != null && maxLayersParam > 0) ? maxLayersParam : Integer.MAX_VALUE;
		       
		       // Parse and cache class sets for O(1) lookup
		       Set<String> tgtClsSet = Arrays.stream(params.getStringParameterValue("tgtCls").split(","))
		           .map(String::strip)
		           .filter(s -> !s.isEmpty())
		           .collect(Collectors.toSet());
		       
		       
//		       Set<String> optClsSet = Arrays.stream(params.getStringParameterValue("optCls").split(","))
//		           .map(String::strip)
//		           .filter(s -> !s.isEmpty() && !tgtClsSet.contains(s))
//		           .collect(Collectors.toSet());
				
		        // Synchronizing ArrayList in Java  
//		        List<String> availPathClassList = Collections.synchronizedList(new ArrayList<String>());  
		        
		       Set<String> optClsSet = Collections.synchronizedSet(new HashSet<String>());  
		       
				hierarchy.getDetectionObjects().parallelStream().forEach(d -> {
					PathClass dpthCls = profilingType == 0? d.getPathClass():
					d.getParent().isAnnotation()? d.getParent().getPathClass(): null;
							
					synchronized(optClsSet) {
						if(dpthCls != null) { 
							if(!tgtClsSet.contains(dpthCls.toString()) && !optClsSet.contains(dpthCls.toString())) {
								optClsSet.add(dpthCls.toString());
							}
						}		
					}
				});
				
				
		    		   
		    		   
		    		   
		    		   
		    		   
		    		   
		    		   
		    		   
		    		   
		    		   
		    		   
		    		   
		    		   
		    		   
		    		   
		    		   
		    		   
		    		   
		    		   
		    		   
		    		   
		    		   
		       // Clear existing measurements in parallel
		       allDetectionPathObjectList.parallelStream().forEach(d -> {
		           MeasurementList dMeasList = d.getMeasurementList();
		           if(dMeasList.containsKey(measurementKey)) {
		               dMeasList.remove(measurementKey);
		           }
		       });
		       // Pre-compute cell classifications to avoid repeated string operations
		       Map<PathObject, String> cellClassMap = new ConcurrentHashMap<>();
		       allDetectionPathObjectList.parallelStream().forEach(cell -> {
		           PathClass pathClass = useDetectionType ? cell.getPathClass() :
		               (cell.getParent().isAnnotation() ? cell.getParent().getPathClass() : null);
		           if(pathClass != null) {
		               cellClassMap.put(cell, pathClass.toString().strip());
		           }
		       });
		       // Filter cells that are valid targets
		       Set<PathObject> validTargetCells = cellClassMap.entrySet().stream()
		           .filter(entry -> tgtClsSet.contains(entry.getValue()))
		           .map(Map.Entry::getKey)
		           .collect(Collectors.toSet());
		       if(validTargetCells.isEmpty()) {
		           logger.warn("No valid target cells found");
		           return new ArrayList<>(hierarchy.getRootObject().getChildObjects());
		       }
		       // BFS-based approach for much better performance
		       runBFSSpatialAnalysis(connections, validTargetCells, cellClassMap, tgtClsSet, optClsSet, measurementKey, maxLayers);
		       hierarchy.getSelectionModel().setSelectedObject(null);
		   } catch(Exception e) {
		       lastResults = e.getMessage();
		       logger.error(lastResults);
//		       Dialogs.showErrorMessage("Error", e.getMessage());
		   }
		   if (Thread.currentThread().isInterrupted()) {
		       lastResults = "Interrupted!";
		       logger.warn(lastResults);
//		       Dialogs.showErrorMessage("Warning", "Interrupted!");
		   }
		   return new ArrayList<>(hierarchy.getRootObject().getChildObjects());
		}
		/**
		* Optimized BFS-based spatial analysis that processes cells layer by layer
		* without redundant scanning. Time complexity: O(cells + connections)
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
		   // BFS propagation inward - alternate between the two queues
		   int layer = 1;
		   while(layer < maxLayers) {
		       final int currentLayerNum = layer;
		       // Determine which queue is current and which is next based on layer parity
		       final Queue<PathObject> currentLayerQueue = (layer % 2 == 1) ? layerA : layerB;
		       final Queue<PathObject> nextLayerQueue = (layer % 2 == 1) ? layerB : layerA;
		       if(currentLayerQueue.isEmpty()) {
		           break; // No more cells to process
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
		}
			
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
