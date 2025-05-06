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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import java.awt.image.BufferedImage;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.beans.property.StringProperty;
import javafx.beans.property.IntegerProperty;

import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.prefs.PathPrefs;
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
public class CellSpatialProfiling extends AbstractDetectionPlugin<BufferedImage> {
	
	final private static Logger logger = LoggerFactory.getLogger(CellSpatialProfiling.class);
	
	private StringProperty sptAnalTgtClsProp = PathPrefs.createPersistentPreference("sptAnalTgtCls", ""); 
	private StringProperty sptAnalOptClsProp = PathPrefs.createPersistentPreference("sptAnalOptCls", ""); 
	private StringProperty sptAnalTypeProp = PathPrefs.createPersistentPreference("sptAnalType", "detection"); 
	private StringProperty sptAnalIdProp = PathPrefs.createPersistentPreference("sptAnalId", "default"); 
	
	private IntegerProperty sptAnalLayersProp = PathPrefs.createPersistentPreference("sptAnalLayer", 1000); 
	private ParameterList params;
	
	private String lastResults = null;
	private List<String> profilingTypeList = List.of("detection", "annotation");
	private int profilingType = 0;
	
	/**
	 * Constructor.
	 * @throws Exception 
	 */
	public CellSpatialProfiling() throws Exception {
		PathObjectHierarchy hierarchy = QP.getCurrentImageData().getHierarchy();
		
        // Synchronizing ArrayList in Java  
        List<String> availPathClassList = Collections.synchronizedList(new ArrayList<String>());  
        List<String> selectedPathClassList = Collections.synchronizedList(new ArrayList<String>());  
        
        int sltdAnnotNum = hierarchy.getSelectionModel().getSelectedObjects().stream().filter(e -> e.isAnnotation()).collect(Collectors.toList()).size();
        int sltdDetNum = hierarchy.getSelectionModel().getSelectedObjects().stream().filter(e -> e.isDetection()).collect(Collectors.toList()).size();
        
        if(sltdAnnotNum > 0 && sltdDetNum == 0) {
        	profilingType = 1;
        }
        else if(sltdAnnotNum > 0 && sltdDetNum > 0) {
        	throw new Exception("do not select both annotations and detections.");
        }
        
		hierarchy.getDetectionObjects().parallelStream().forEach(d -> {
//		for(PathObject d: hierarchy.getDetectionObjects()) {
			
			PathClass dpthCls = profilingType == 0? d.getPathClass():
			d.getParent().isAnnotation()? d.getParent().getPathClass(): null;
					
			if(dpthCls != null) {
				synchronized (availPathClassList) {  
					if(!availPathClassList.contains(dpthCls.getName())) {
						availPathClassList.add(dpthCls.getName());
					}
				}
			}			
		});
//		}
		
		hierarchy.getSelectionModel().getSelectedObjects().parallelStream().forEach(d -> {
//		for(PathObject d: hierarchy.getSelectionModel().getSelectedObjects()) {
			
			PathClass dpthCls = d.getPathClass();
			
			if(dpthCls != null) {
				synchronized (availPathClassList) {  
					if(availPathClassList.contains(dpthCls.getName())) {
						availPathClassList.remove(dpthCls.getName());
					}
				}
				
				synchronized (selectedPathClassList) {  
					if(!selectedPathClassList.contains(dpthCls.getName())) {
						selectedPathClassList.add(dpthCls.getName());
					}
				}
			}
		});
//		}
		
		String posClsList = String.join(",", selectedPathClassList);
		String negClsList = String.join(",", availPathClassList);
		
		params = new ParameterList()
			.addTitleParameter("Layerwise Spatial Analysis")
			.addStringParameter("tgtCls", "Targeting Class(es)", posClsList, "Targeting Class(es)")
			.addStringParameter("optCls", "Opponent Class(es)", negClsList, "Opponent Class(es)")
			.addChoiceParameter("type", "Profiling by", profilingTypeList.get(profilingType), profilingTypeList, "Profiling options")
			.addStringParameter("id", "Layer ID", sptAnalIdProp.get(), "Layer ID")
			.addIntParameter("layers", "Maximal layers of detection", sptAnalLayersProp.get(), null, "Maximal layers of detection")			
			;
	}
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		
		@Override
		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
			sptAnalTgtClsProp.set(params.getStringParameterValue("tgtCls"));
			sptAnalOptClsProp.set(params.getStringParameterValue("optCls"));
			sptAnalTypeProp.set((String)params.getChoiceParameterValue("type"));
			sptAnalIdProp.set(params.getStringParameterValue("id"));
			sptAnalLayersProp.set(params.getIntParameterValue("layers"));
			
			PathObjectHierarchy hierarchy = imageData.getHierarchy();
			
			
			try {
	            /*
	             * Generate cell masks with their labels
	             */
				
				PathObjectConnections connections = (PathObjectConnections) imageData.getProperty("OBJECT_CONNECTIONS");
				
				if(connections == null) throw new Exception("Connections generated using Delaunay clustering are required.");
				
				List<PathObject> selectedAnnotationPathObjectList = 
					hierarchy.
					getSelectionModel().
					getSelectedObjects().
					stream().
					filter(e -> e.isAnnotation() && e.hasChildObjects()).
					collect(Collectors.toList());
					
				if(selectedAnnotationPathObjectList.isEmpty()) throw new Exception("Missed selected annotations");

				List<PathObject> allDetectionPathObjectList = Collections.synchronizedList(new ArrayList<>());
				
				selectedAnnotationPathObjectList.parallelStream().forEach(p -> {
					synchronized(allDetectionPathObjectList) {
						allDetectionPathObjectList.addAll(p.getChildObjects().stream().filter(e -> e.isDetection()).collect(Collectors.toList()));
					}
				});
				
				allDetectionPathObjectList.parallelStream().forEach(d -> {
					MeasurementList dMeasList = d.getMeasurementList();
					
					if(dMeasList.containsKey("layer:"+params.getStringParameterValue("id"))) {
						dMeasList.remove("layer:"+params.getStringParameterValue("id"));
						dMeasList.close();
					}						
				});
				
				List<String> tgtClsLst = 
						Arrays.stream(params.getStringParameterValue("tgtCls").
						split(",")).
						map(s -> s.strip()).
						filter(e -> !e.isEmpty()).
						collect(Collectors.toList());
				
				List<String> optClsLst = 
						Arrays.stream(params.getStringParameterValue("optCls").
						split(",")).
						map(s -> s.strip()).
						filter(e -> !tgtClsLst.contains(e) && !e.isEmpty()).
						collect(Collectors.toList());
				
				for(int l = 0; l < params.getIntParameterValue("layers"); l ++) {
					int layer = l;
					AtomicBoolean terminate_flag = new AtomicBoolean(true);

					allDetectionPathObjectList.parallelStream().forEach(c -> {
//					for(PathObject c: allDetectionPathObjectList) {	
						
							if(c.getMeasurementList().containsKey("layer:"+params.getStringParameterValue("id"))) 
								return;
//								continue;
							
							PathClass cPthCls = ((String)params.getChoiceParameterValue("type")).equals(profilingTypeList.get(0))? c.getPathClass(): 
								c.getParent().isAnnotation()? c.getParent().getPathClass(): null;
							
							if(cPthCls == null) 
								return;
//								continue;
							
							String cCls = cPthCls.toString().strip();
							
							if(tgtClsLst.stream().anyMatch(cCls::equals)) {
								List<PathObject> connectedObj = connections.getConnections(c);
								
								for(PathObject d: connectedObj) {
									PathClass dPthCls = ((String)params.getChoiceParameterValue("type")).equals(profilingTypeList.get(0))? d.getPathClass():
										d.getParent().isAnnotation()? d.getParent().getPathClass(): null;
									
									if(dPthCls == null) continue;
									
									if(layer == 0) {
										String dCls = dPthCls.toString().strip();
										
										if(optClsLst.stream().anyMatch(dCls::equals)) {
											
											synchronized(c) {
												MeasurementList tgtObjMeasList = c.getMeasurementList();
												tgtObjMeasList.put("layer:"+params.getStringParameterValue("id"), layer+1);
												tgtObjMeasList.close();
											}
											
											terminate_flag.set(false);
											break;
										}
									}
									else {
										MeasurementList optObjMeasList = d.getMeasurementList();
										Double v = optObjMeasList.get("layer:"+params.getStringParameterValue("id"));
										
										if((!v.isNaN()) && (v.intValue() == layer)) {
											
											synchronized(c) {
												MeasurementList tgtObjMeasList = c.getMeasurementList();
												tgtObjMeasList.put("layer:"+params.getStringParameterValue("id"), layer+1);
												tgtObjMeasList.close();
											}
											
											terminate_flag.set(false);
											break;
										}	
									}
								}
							}	
							
//						});
//						}
//					});
//					}
							
					});
//					}
					
					if(terminate_flag.get()) break;
				}
				
		        hierarchy.getSelectionModel().setSelectedObject(null);
			}
			catch(Exception e) {
				Dialogs.showErrorMessage("Error", e.getMessage());
				lastResults = e.getMessage();
				logger.error(lastResults);
			}				
			
			if (Thread.currentThread().isInterrupted()) {

				Dialogs.showErrorMessage("Warning", "Interrupted!");
				lastResults =  "Interrupted!";
				logger.warn(lastResults);
			}
			
			return new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
		}
		
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
		return "Simple tissue detection";
	}

	@Override
	public String getLastResultsDescription() {
		return lastResults;
	}

	@Override
	public String getDescription() {
		return "Detect one or more regions of interest by applying a global threshold";
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
		list.add(PathRootObject.class);
		return list;		

//		return Arrays.asList(
//				PathAnnotationObject.class,
//				TMACoreObject.class
//				);
	}
}
