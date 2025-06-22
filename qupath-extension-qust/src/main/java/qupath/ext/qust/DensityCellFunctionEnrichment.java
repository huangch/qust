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

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.StringProperty;
//import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectConnections;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractDetectionPlugin;
import qupath.lib.plugins.DetectionPluginTools;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;

/**
 * Plugin for loading 10x Visium Annotation 
 * 
 * @author Chao Hui Huang
 *
 */

public class DensityCellFunctionEnrichment extends AbstractDetectionPlugin<BufferedImage> {
	private static Logger logger = LoggerFactory.getLogger(DensityCellFunctionEnrichment.class);

	private StringProperty csdEstTgtClsProp = PathPrefs.createPersistentPreference("csdEstTgtCls", ""); 
	private StringProperty csdEstOptClsProp = PathPrefs.createPersistentPreference("csdEstOptCls", ""); 
	private StringProperty csdEstIdProp = PathPrefs.createPersistentPreference("csdEstId", "default"); 
	private StringProperty csdEstTypeProp = PathPrefs.createPersistentPreference("sptAnalType", "detection"); 
	private DoubleProperty csdEstBandwidthProp = PathPrefs.createPersistentPreference("csdEstBandwidth", 50.0);
	
	private List<String> profilingTypeList = List.of("detection", "annotation");
	private int profilingType = 0;
	private ParameterList params;
	private String lastResults = null;
	
	/**
	 * Constructor.
	 */
	public DensityCellFunctionEnrichment() {
		PathObjectHierarchy hierarchy = QP.getCurrentImageData().getHierarchy();
		
        // Synchronizing ArrayList in Java  
        List<String> availPathClassList = Collections.synchronizedList(new ArrayList<String>());  
        List<String> selectedPathClassList = Collections.synchronizedList(new ArrayList<String>());  
        
        int sltdAnnotNum = hierarchy.getSelectionModel().getSelectedObjects().stream().filter(e -> e.isAnnotation()).collect(Collectors.toList()).size();
        int sltdDetNum = hierarchy.getSelectionModel().getSelectedObjects().stream().filter(e -> e.isDetection()).collect(Collectors.toList()).size();
        
//        if(sltdAnnotNum == 0 && sltdDetNum > 0) {
//        	profilingType = 0;
//        }
//        else 
        if(sltdAnnotNum > 0 && sltdDetNum == 0) {
        	profilingType = 1;
        }
//        else {
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
		
		String posClsList = String.join(",", selectedPathClassList);
		String negClsList = String.join(",", availPathClassList);
		
		params = new ParameterList()
			.addTitleParameter("Density-based Cell Function Enrichment Analysis - Neighboring cell function enrichment analysis based on KDE")
			.addStringParameter("tgtCls", "Chosen classes of cell functions", posClsList, "Targeting Class(es)")
			.addStringParameter("optCls", "Rests", negClsList, "Opponent Class(es)")
			.addChoiceParameter("type", "Profiling by", profilingTypeList.get(profilingType), profilingTypeList, "Profiling options")
			.addDoubleParameter("bandwidth", "Bandwidth", 50.0, null, "Bandwidth")
			.addStringParameter("id", "ID", csdEstIdProp.get(), "ID")
			;
	}
	
	class SpatialDensityEstimation implements ObjectDetector<BufferedImage> {
	

		
		@Override
		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
		   // Cache parameter values to avoid repeated lookups
		   String tgtCls = params.getStringParameterValue("tgtCls");
		   String optCls = params.getStringParameterValue("optCls");
		   String type = (String)params.getChoiceParameterValue("type");
		   double bandwidth = params.getDoubleParameterValue("bandwidth");
		   String id = params.getStringParameterValue("id");
		   // Set properties (keeping original behavior)
		   csdEstTgtClsProp.set(tgtCls);
		   csdEstOptClsProp.set(optCls);
		   csdEstTypeProp.set(type);
		   csdEstBandwidthProp.set(bandwidth);
		   csdEstIdProp.set(id);
		   // Precompute constants
		   String measurementKey = "dcfe:" + id;
		   double bw2_inv = 1.0 / (2 * bandwidth * bandwidth);
		   double maxDistance = 5 * bandwidth; // Beyond 5 standard deviations, contribution is negligible
		   double maxDistanceSquared = maxDistance * maxDistance;
		   PathObjectHierarchy hierarchy = imageData.getHierarchy();
		   try {
		       // Validate connections
		       PathObjectConnections connections = (PathObjectConnections) imageData.getProperty("OBJECT_CONNECTIONS");
		       if(connections == null) {
		           throw new Exception("Connections generated using Delaunay clustering are required.");
		       }
		       // Get selected annotations
		       List<PathObject> selectedAnnotationPathObjectList = hierarchy
		           .getSelectionModel()
		           .getSelectedObjects()
		           .stream()
		           .filter(e -> e.isAnnotation() && e.hasChildObjects())
		           .collect(Collectors.toList());
		       if(selectedAnnotationPathObjectList.isEmpty()) {
		           throw new Exception("Missed selected annotations");
		       }
		       // Collect all detection objects efficiently
		       List<PathObject> allDetectionPathObjectList = selectedAnnotationPathObjectList
		           .parallelStream()
		           .flatMap(p -> p.getChildObjects().stream())
		           .filter(PathObject::isDetection)
		           .collect(Collectors.toList());
		       // Remove existing measurements in parallel
		       allDetectionPathObjectList.parallelStream().forEach(d -> {
		           MeasurementList dMeasList = d.getMeasurementList();
		           if(dMeasList.containsKey(measurementKey)) {
		               dMeasList.remove(measurementKey);
		           }
		       });
		       // Parse target classes efficiently
		       List<String> tgtClsLst = Arrays.stream(tgtCls.split(","))
		           .map(String::strip)
		           .filter(s -> !s.isEmpty())
		           .collect(Collectors.toList());
		       // Create a Set for faster lookup
		       Set<String> tgtClsSet = new HashSet<>(tgtClsLst);
		       // Filter KDE detection objects efficiently
		       List<PathObject> kdeDetectionPathObjectList = allDetectionPathObjectList
		           .parallelStream()
		           .filter(c -> {
		               // Skip if already has measurement
		               if(c.getMeasurementList().containsKey(measurementKey)) {
		                   return false;
		               }
		               // Determine path class based on type
		               PathClass cPthCls = type.equals(profilingTypeList.get(0)) ?
		                   c.getPathClass() :
		                   (c.getParent().isAnnotation() ? c.getParent().getPathClass() : null);
		               if(cPthCls == null) {
		                   return false;
		               }
		               String cCls = cPthCls.toString().strip();
		               return tgtClsSet.contains(cCls); // O(1) lookup instead of O(n)
		           })
		           .collect(Collectors.toList());
		       // Extract coordinates efficiently
		       int kdeSize = kdeDetectionPathObjectList.size();
		       double[] xData = new double[kdeSize];
		       double[] yData = new double[kdeSize];
		       // Parallel coordinate extraction
		       IntStream.range(0, kdeSize).parallel().forEach(i -> {
		           ROI roi = kdeDetectionPathObjectList.get(i).getROI();
		           xData[i] = roi.getCentroidX();
		           yData[i] = roi.getCentroidY();
		       });
		       // Compute KDE for all detection objects
		       allDetectionPathObjectList.parallelStream().forEach(c -> {
		           double xq = c.getROI().getCentroidX();
		           double yq = c.getROI().getCentroidY();
		           double sum = 0.0;
		           // Optimized KDE calculation with spatial filtering
		           for(int i = 0; i < kdeSize; i++) {
		               double dx = xq - xData[i];
		               double dy = yq - yData[i];
		               double distanceSquared = dx * dx + dy * dy;
		               // Skip cells that are too far away (beyond 5 standard deviations)
		               if(distanceSquared <= maxDistanceSquared) {
		                   sum += Math.exp(-distanceSquared * bw2_inv);
		               }
		           }
		           // Store the result
		           c.getMeasurementList().put(measurementKey, sum);
		       });
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
		return "Density-based Cell Function Enrichment Analysis";
	}

	@Override
	public String getLastResultsDescription() {
		return lastResults;
	}


	@Override
	public String getDescription() {
		return "Neighboring cell function enrichment analysis based on KDE";
	}


	@Override
	protected void addRunnableTasks(ImageData<BufferedImage> imageData, PathObject parentObject, List<Runnable> tasks) {
		tasks.add(DetectionPluginTools.createRunnableTask(new SpatialDensityEstimation(), getParameterList(imageData), imageData, parentObject));
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
//		List<Class<? extends PathObject>> list = new ArrayList<>();
//		list.add(TMACoreObject.class);
//		list.add(PathRootObject.class);
//		return list;
		
		return Arrays.asList(
			PathAnnotationObject.class,
			TMACoreObject.class
			);		
	}

}



///*-
// * #%L
// * ST-AnD is free software: you can redistribute it and/or modify
// * it under the terms of the GNU General Public License as
// * published by the Free Software Foundation, either version 3 of the
// * License, or (at your option) any later version.
// * 
// * ST-AnD is distributed in the hope that it will be useful,
// * but WITHOUT ANY WARRANTY; without even the implied warranty of
// * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// * GNU General Public License for more details.
// * 
// * You should have received a copy of the GNU General Public License 
// * along with ST-AnD.  If not, see <https://www.gnu.org/licenses/>.
// * #L%
// */
//
//package qupath.ext.qust;
//
//import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.Collection;
//import java.util.Collections;
//import java.util.HashSet;
//import java.util.List;
//import java.util.Set;
//import java.util.stream.Collectors;
//import java.util.stream.IntStream;
//import java.awt.image.BufferedImage;
//import java.io.IOException;
//
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import javafx.beans.property.StringProperty;
//import javafx.beans.property.DoubleProperty;
//
//import qupath.fx.dialogs.Dialogs;
//import qupath.lib.gui.prefs.PathPrefs;
//import qupath.lib.objects.PathAnnotationObject;
//import qupath.lib.objects.PathObject;
//import qupath.lib.objects.PathObjectConnections;
//import qupath.lib.objects.PathRootObject;
//import qupath.lib.objects.classes.PathClass;
//import qupath.lib.objects.hierarchy.PathObjectHierarchy;
//import qupath.lib.plugins.AbstractDetectionPlugin;
//import qupath.lib.plugins.DetectionPluginTools;
//import qupath.lib.plugins.ObjectDetector;
//import qupath.lib.plugins.parameters.ParameterList;
//import qupath.lib.images.ImageData;
//import qupath.lib.roi.interfaces.ROI;
//import qupath.lib.scripting.QP;
//import qupath.lib.measurements.MeasurementList;
//
///**
// * Plugin for loading 10x Visium Annotation 
// * 
// * @author Chao Hui Huang
// *
// */
//public class CellSpatialDensityEstimation extends AbstractDetectionPlugin<BufferedImage> {
//	
//	final private static Logger logger = LoggerFactory.getLogger(CellSpatialDensityEstimation.class);
//	
//	private StringProperty csdEstTgtClsProp = PathPrefs.createPersistentPreference("csdEstTgtCls", ""); 
//	private StringProperty csdEstOptClsProp = PathPrefs.createPersistentPreference("csdEstOptCls", ""); 
//	private StringProperty csdEstIdProp = PathPrefs.createPersistentPreference("csdEstId", "default"); 
//	private StringProperty csdEstTypeProp = PathPrefs.createPersistentPreference("sptAnalType", "detection"); 
//	private DoubleProperty csdEstBandwidthProp = PathPrefs.createPersistentPreference("csdEstBandwidth", 0.5);
//	private ParameterList params;
//	
//	private String lastResults = null;
//	private List<String> profilingTypeList = List.of("detection", "annotation");
//	private int profilingType = 0;
//	
//	/**
//	 * Constructor.
//	 * @throws Exception 
//	 */
//	public CellSpatialDensityEstimation() throws Exception {
//		PathObjectHierarchy hierarchy = QP.getCurrentImageData().getHierarchy();
//		
//        // Synchronizing ArrayList in Java  
//        List<String> availPathClassList = Collections.synchronizedList(new ArrayList<String>());  
//        List<String> selectedPathClassList = Collections.synchronizedList(new ArrayList<String>());  
//        
//        int sltdAnnotNum = hierarchy.getSelectionModel().getSelectedObjects().stream().filter(e -> e.isAnnotation()).collect(Collectors.toList()).size();
//        int sltdDetNum = hierarchy.getSelectionModel().getSelectedObjects().stream().filter(e -> e.isDetection()).collect(Collectors.toList()).size();
//        
//        if(sltdAnnotNum == 0 && sltdDetNum == 0) {
//        	throw new Exception("need either annotations or detections.");
//        }
//        else if(sltdAnnotNum == 0 && sltdDetNum > 0) {
//        	profilingType = 0;
//        }
//        else if(sltdAnnotNum > 0 && sltdDetNum == 0) {
//        	profilingType = 1;
//        }
//        else if(sltdAnnotNum > 0 && sltdDetNum > 0) {
//        	throw new Exception("do not select both annotations and detections.");
//        }
//
//		hierarchy.getDetectionObjects().parallelStream().forEach(d -> {
//			PathClass dpthCls = profilingType == 0? d.getPathClass():
//			d.getParent().isAnnotation()? d.getParent().getPathClass(): null;
//					
//			synchronized(availPathClassList) {
//				if(dpthCls != null) { 
//					if(!availPathClassList.contains(dpthCls.getName())) {
//						availPathClassList.add(dpthCls.getName());
//					}
//				}		
//			}
//		});
//		
//		hierarchy.getSelectionModel().getSelectedObjects().parallelStream().forEach(d -> {
//			PathClass dpthCls = d.getPathClass();
//			
//			if(dpthCls != null) {
//				synchronized (availPathClassList) {  
//					if(availPathClassList.contains(dpthCls.getName())) {
//						availPathClassList.remove(dpthCls.getName());
//					}
//				}
//				
//				synchronized (selectedPathClassList) {  
//					if(!selectedPathClassList.contains(dpthCls.getName())) {
//						selectedPathClassList.add(dpthCls.getName());
//					}
//				}
//			}
//		});
//		
//		String posClsList = String.join(",", selectedPathClassList);
//		String negClsList = String.join(",", availPathClassList);
//		
//		params = new ParameterList()
//			.addTitleParameter("Layerwise Spatial Analysis")
//			.addStringParameter("tgtCls", "Targeting Class(es)", posClsList, "Targeting Class(es)")
//			.addStringParameter("optCls", "Opponent Class(es)", negClsList, "Opponent Class(es)")
//			.addChoiceParameter("type", "Profiling by", profilingTypeList.get(profilingType), profilingTypeList, "Profiling options")
//			.addDoubleParameter("bandwidth", "Bandwidth", 0.5, null, "Bandwidth")
//			.addStringParameter("id", "KDE ID", csdEstIdProp.get(), "KDE ID")
//			;
//	}
//	
//	class SpatialDensityEstimation implements ObjectDetector<BufferedImage> {
//		
////		private static double gaussianKernel(double dx, double dy, double bandwidth) {
////			double bw2 = bandwidth * bandwidth;
////			double r2 = dx * dx + dy * dy;
////			return Math.exp(-r2 / (2 * bw2));
////		}
////		
////		@Override
////		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
////			csdEstTgtClsProp.set(params.getStringParameterValue("tgtCls"));
////			csdEstOptClsProp.set(params.getStringParameterValue("optCls"));
////			csdEstTypeProp.set((String)params.getChoiceParameterValue("type"));
////			csdEstBandwidthProp.set(params.getDoubleParameterValue("bandwidth"));
////			csdEstIdProp.set(params.getStringParameterValue("id"));
////			
////			PathObjectHierarchy hierarchy = imageData.getHierarchy();
////			
////			try {
////	            /*
////	             * Generate cell masks with their labels
////	             */
////				
////				PathObjectConnections connections = (PathObjectConnections) imageData.getProperty("OBJECT_CONNECTIONS");
////				
////				if(connections == null) throw new Exception("Connections generated using Delaunay clustering are required.");
////				
////				List<PathObject> selectedAnnotationPathObjectList = 
////					hierarchy.
////					getSelectionModel().
////					getSelectedObjects().
////					stream().
////					filter(e -> e.isAnnotation() && e.hasChildObjects()).
////					collect(Collectors.toList());
////					
////				if(selectedAnnotationPathObjectList.isEmpty()) throw new Exception("Missed selected annotations");
////
////				List<PathObject> allDetectionPathObjectList = Collections.synchronizedList(new ArrayList<>());
////				
////				selectedAnnotationPathObjectList.stream().forEach(p -> {
////					allDetectionPathObjectList.addAll(p.getChildObjects().stream().filter(e -> e.isDetection()).collect(Collectors.toList()));
////				});
////				
////				allDetectionPathObjectList.parallelStream().forEach(d -> {
////					MeasurementList dMeasList = d.getMeasurementList();
////					if(dMeasList.containsKey("csde:"+params.getStringParameterValue("id"))) {
////						dMeasList.remove("csde:"+params.getStringParameterValue("id"));
////					}						
////				});
////				
////				List<String> tgtClsLst = 
////						Arrays.stream(params.getStringParameterValue("tgtCls").
////						split(",")).
////						map(s -> s.strip()).
////						filter(e -> !e.isEmpty()).
////						collect(Collectors.toList());
////				
//////				List<String> optClsLst = 
//////						Arrays.stream(params.getStringParameterValue("optCls").
//////						split(",")).
//////						map(s -> s.strip()).
//////						filter(e -> !tgtClsLst.contains(e) && !e.isEmpty()).
//////						collect(Collectors.toList());
////				
////				List<PathObject> kdeDetectionPathObjectList = Collections.synchronizedList(new ArrayList<>());
////
////				allDetectionPathObjectList.parallelStream().forEach(c -> {
//////				for(PathObject c: allDetectionPathObjectList) {
////					if(c.getMeasurementList().containsKey("csde:"+params.getStringParameterValue("id"))) 
////						return;
//////						continue;
////					
////					PathClass cPthCls = ((String)params.getChoiceParameterValue("type")).equals(profilingTypeList.get(0))? c.getPathClass(): 
////						c.getParent().isAnnotation()? c.getParent().getPathClass(): null;
////					
////					if(cPthCls == null) 
////						return;
//////						continue;
////					
////					String cCls = cPthCls.toString().strip();
////					
////					synchronized(kdeDetectionPathObjectList) {
////						if(tgtClsLst.stream().anyMatch(cCls::equals)) {
////							kdeDetectionPathObjectList.add(c);
////						}	
////					}	
////				});
//////				}
////				
////				double[] xData = new double[kdeDetectionPathObjectList.size()];
////				double[] yData = new double[kdeDetectionPathObjectList.size()];
////				
////				IntStream.range(0, kdeDetectionPathObjectList.size()).parallel().forEach(i -> {
////					xData[i] = kdeDetectionPathObjectList.get(i).getROI().getCentroidX();
////					yData[i] = kdeDetectionPathObjectList.get(i).getROI().getCentroidY();
////				});
////				
////				allDetectionPathObjectList.parallelStream().forEach(c -> {
//////				for(PathObject c: allDetectionPathObjectList) {
////				
////					double xq = c.getROI().getCentroidX();
////					double yq = c.getROI().getCentroidY();
////					double sum = 0.0;
////					
////					for( int i = 0; i < kdeDetectionPathObjectList.size(); i ++) {
////						double dx = xq - xData[i];
////						double dy = yq - yData[i];
////						sum += gaussianKernel(dx, dy, params.getDoubleParameterValue("bandwidth"));
////						
////					}
////					
////					String id = !params.getStringParameterValue("id").isBlank()? ":"+params.getStringParameterValue("id"): "";
////						
////					c.getMeasurementList().put("csde:"+id, sum);
////				});
//////				}
////				
////		        hierarchy.getSelectionModel().setSelectedObject(null);
////			}
////			catch(Exception e) {
////				lastResults = e.getMessage();
////				logger.error(lastResults);
////				Dialogs.showErrorMessage("Error", e.getMessage());
////			}				
////			
////			if (Thread.currentThread().isInterrupted()) {
////				lastResults =  "Interrupted!";
////				logger.warn(lastResults);
////				Dialogs.showErrorMessage("Warning", "Interrupted!");
////			}
////			
////			return new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
////		}
//		
//		@Override
//		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
//		   // Cache parameter values to avoid repeated lookups
//		   String tgtCls = params.getStringParameterValue("tgtCls");
//		   String optCls = params.getStringParameterValue("optCls");
//		   String type = (String)params.getChoiceParameterValue("type");
//		   double bandwidth = params.getDoubleParameterValue("bandwidth");
//		   String id = params.getStringParameterValue("id");
//		   // Set properties (keeping original behavior)
//		   csdEstTgtClsProp.set(tgtCls);
//		   csdEstOptClsProp.set(optCls);
//		   csdEstTypeProp.set(type);
//		   csdEstBandwidthProp.set(bandwidth);
//		   csdEstIdProp.set(id);
//		   // Precompute constants
//		   String measurementKey = "csde:" + (!id.isBlank() ? id : "");
//		   double bw2_inv = 1.0 / (2 * bandwidth * bandwidth);
//		   double maxDistance = 5 * bandwidth; // Beyond 5 standard deviations, contribution is negligible
//		   double maxDistanceSquared = maxDistance * maxDistance;
//		   PathObjectHierarchy hierarchy = imageData.getHierarchy();
//		   try {
//		       // Validate connections
//		       PathObjectConnections connections = (PathObjectConnections) imageData.getProperty("OBJECT_CONNECTIONS");
//		       if(connections == null) {
//		           throw new Exception("Connections generated using Delaunay clustering are required.");
//		       }
//		       // Get selected annotations
//		       List<PathObject> selectedAnnotationPathObjectList = hierarchy
//		           .getSelectionModel()
//		           .getSelectedObjects()
//		           .stream()
//		           .filter(e -> e.isAnnotation() && e.hasChildObjects())
//		           .collect(Collectors.toList());
//		       if(selectedAnnotationPathObjectList.isEmpty()) {
//		           throw new Exception("Missed selected annotations");
//		       }
//		       // Collect all detection objects efficiently
//		       List<PathObject> allDetectionPathObjectList = selectedAnnotationPathObjectList
//		           .parallelStream()
//		           .flatMap(p -> p.getChildObjects().stream())
//		           .filter(PathObject::isDetection)
//		           .collect(Collectors.toList());
//		       // Remove existing measurements in parallel
//		       allDetectionPathObjectList.parallelStream().forEach(d -> {
//		           MeasurementList dMeasList = d.getMeasurementList();
//		           if(dMeasList.containsKey(measurementKey)) {
//		               dMeasList.remove(measurementKey);
//		           }
//		       });
//		       // Parse target classes efficiently
//		       List<String> tgtClsLst = Arrays.stream(tgtCls.split(","))
//		           .map(String::strip)
//		           .filter(s -> !s.isEmpty())
//		           .collect(Collectors.toList());
//		       // Create a Set for faster lookup
//		       Set<String> tgtClsSet = new HashSet<>(tgtClsLst);
//		       // Filter KDE detection objects efficiently
//		       List<PathObject> kdeDetectionPathObjectList = allDetectionPathObjectList
//		           .parallelStream()
//		           .filter(c -> {
//		               // Skip if already has measurement
//		               if(c.getMeasurementList().containsKey(measurementKey)) {
//		                   return false;
//		               }
//		               // Determine path class based on type
//		               PathClass cPthCls = type.equals(profilingTypeList.get(0)) ?
//		                   c.getPathClass() :
//		                   (c.getParent().isAnnotation() ? c.getParent().getPathClass() : null);
//		               if(cPthCls == null) {
//		                   return false;
//		               }
//		               String cCls = cPthCls.toString().strip();
//		               return tgtClsSet.contains(cCls); // O(1) lookup instead of O(n)
//		           })
//		           .collect(Collectors.toList());
//		       // Extract coordinates efficiently
//		       int kdeSize = kdeDetectionPathObjectList.size();
//		       double[] xData = new double[kdeSize];
//		       double[] yData = new double[kdeSize];
//		       // Parallel coordinate extraction
//		       IntStream.range(0, kdeSize).parallel().forEach(i -> {
//		           ROI roi = kdeDetectionPathObjectList.get(i).getROI();
//		           xData[i] = roi.getCentroidX();
//		           yData[i] = roi.getCentroidY();
//		       });
//		       // Compute KDE for all detection objects
//		       allDetectionPathObjectList.parallelStream().forEach(c -> {
//		           double xq = c.getROI().getCentroidX();
//		           double yq = c.getROI().getCentroidY();
//		           double sum = 0.0;
//		           // Optimized KDE calculation with spatial filtering
//		           for(int i = 0; i < kdeSize; i++) {
//		               double dx = xq - xData[i];
//		               double dy = yq - yData[i];
//		               double distanceSquared = dx * dx + dy * dy;
//		               // Skip cells that are too far away (beyond 5 standard deviations)
//		               if(distanceSquared <= maxDistanceSquared) {
//		                   sum += Math.exp(-distanceSquared * bw2_inv);
//		               }
//		           }
//		           // Store the result
//		           c.getMeasurementList().put(measurementKey, sum);
//		       });
//		       hierarchy.getSelectionModel().setSelectedObject(null);
//		   } catch(Exception e) {
//		       lastResults = e.getMessage();
//		       logger.error(lastResults);
//		       Dialogs.showErrorMessage("Error", e.getMessage());
//		   }
//		   if (Thread.currentThread().isInterrupted()) {
//		       lastResults = "Interrupted!";
//		       logger.warn(lastResults);
//		       Dialogs.showErrorMessage("Warning", "Interrupted!");
//		   }
//		   return new ArrayList<>(hierarchy.getRootObject().getChildObjects());
//		}
//			
//		@Override
//		public String getLastResultsDescription() {
//			return lastResults;
//		}
//	}
//
//	@Override
//	public ParameterList getDefaultParameterList(ImageData<BufferedImage> imageData) {
//		return params;
//	}
//
//	@Override
//	public String getName() {
//		return "Simple tissue detection";
//	}
//
//	@Override
//	public String getLastResultsDescription() {
//		return lastResults;
//	}
//
//	@Override
//	public String getDescription() {
//		return "Detect one or more regions of interest by applying a global threshold";
//	}
//
//	@Override
//	protected void addRunnableTasks(ImageData<BufferedImage> imageData, PathObject parentObject, List<Runnable> tasks) {
//		tasks.add(DetectionPluginTools.createRunnableTask(new SpatialDensityEstimation(), getParameterList(imageData), imageData, parentObject));
//	}
//
//
//	@Override
//	protected Collection<? extends PathObject> getParentObjects(ImageData<BufferedImage> imageData) {	
//		PathObjectHierarchy hierarchy = imageData.getHierarchy();
//		if (hierarchy.getTMAGrid() == null)
//			return Collections.singleton(hierarchy.getRootObject());
//		
//		return hierarchy.getSelectionModel().getSelectedObjects().stream().filter(p -> p.isTMACore()).collect(Collectors.toList());
//	}
//
//	@Override
//	public Collection<Class<? extends PathObject>> getSupportedParentObjectClasses() {
//		// Temporarily disabled so as to avoid asking annoying questions when run repeatedly
//		List<Class<? extends PathObject>> list = new ArrayList<>();
////		list.add(TMACoreObject.class);
//		list.add(PathAnnotationObject.class);
//		list.add(PathRootObject.class);
//		return list;		
//
////		return Arrays.asList(
////				PathAnnotationObject.class,
////				TMACoreObject.class
////				);
//	}
//}
