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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.measure.ObservableMeasurementTableData;
import qupath.lib.images.ImageData;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectConnections;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractDetectionPlugin;
import qupath.lib.plugins.DetectionPluginTools;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.roi.interfaces.ROI;

/**
 * Plugin for loading 10x Visium Annotation 
 * 
 * @author Chao Hui Huang
 *
 */
public class CellNeighborAnalysis extends AbstractDetectionPlugin<BufferedImage> {
	final private static Logger logger = LoggerFactory.getLogger(CellNeighborAnalysis.class);
//	private StringProperty CCIAnalLigandReceptorProp = PathPrefs.createPersistentPreference("CNAnalLigandReceptor", "ligand"); 
	
	private ParameterList params;
	protected static QuSTSetup qustSetup = QuSTSetup.getInstance();
//	private List<String> ligandreceptorList = List.of("ligand", "receptor");
	
	private String lastResults = null;
	
	/**
	 * Constructor.
	 */
	public CellNeighborAnalysis() {
		params = new ParameterList()
			.addTitleParameter("Cell Neighbor Analysis")
			.addIntParameter("layers", "No. of layers", 1, null, "No. of layers")
			;
	}
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		@Override
		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
			PathObjectHierarchy hierarchy = imageData.getHierarchy();
			
			try {
				/*
	             * Generate cell masks with their labels
	             */
				
				ObservableMeasurementTableData measTblData = new ObservableMeasurementTableData();
				measTblData.setImageData(imageData, imageData == null ? Collections.emptyList() : hierarchy.getObjects(null, PathDetectionObject.class));
				
				PathObjectConnections connections = (PathObjectConnections) imageData.getProperty("OBJECT_CONNECTIONS");
				if(connections == null) throw new Exception("Connections generated using Delaunay clustering are required.");
				
				List<PathObject> selectedAnnotationPathObjectList = hierarchy
					.getSelectionModel()
					.getSelectedObjects()
					.stream()
					.filter(e -> e.isAnnotation() && e.hasChildObjects())
					.collect(Collectors.toList());
				
				if(selectedAnnotationPathObjectList.isEmpty()) throw new Exception("Missed selected annotations");
				
				List<PathObject> allDetectionPathObjectList = Collections.synchronizedList(new ArrayList<>());
				
				selectedAnnotationPathObjectList.parallelStream().forEach(p -> {
					allDetectionPathObjectList.addAll(p.getChildObjects().stream().filter(e -> e.isDetection()).collect(Collectors.toList()));
				});
				
				
				Set<PathClass> detectionPathClassList = Collections.synchronizedSet(new HashSet<PathClass>());
				
				allDetectionPathObjectList.parallelStream().forEach(p -> {
					detectionPathClassList.add(p.getPathClass());
				});
				
				
				allDetectionPathObjectList.parallelStream().forEach(c -> { 
//				for(PathObject c: allDetectionPathObjectList) {
					List<PathObject> last_searching_objects = Collections.synchronizedList(new ArrayList<>());
					List<PathObject> current_searching_objects = Collections.synchronizedList(new ArrayList<>());
					List<PathObject> connected_objects = Collections.synchronizedList(new ArrayList<>());
						
					last_searching_objects.add(c);	
						
					for(int l = 0; l < params.getIntParameterValue("layers"); l ++) {
//						last_searching_objects.parallelStream().forEach(o -> {
						for(PathObject o: last_searching_objects) {
								
							List<PathObject> neighbors = connections.getConnections(o);
							
//							neighbors.parallelStream().forEach(n -> {
							for(PathObject n: neighbors) {
									
								if(n != c && !current_searching_objects.contains(n) && !last_searching_objects.contains(n)) {
									synchronized(current_searching_objects) {
										current_searching_objects.add(n);
									}
								}
//							});
							}	
//						});
						}
							
						last_searching_objects.clear();
						last_searching_objects.addAll(current_searching_objects);
							
						connected_objects.addAll(current_searching_objects);
							
						current_searching_objects.clear();
					}
						
					MeasurementList cMeasList = c.getMeasurementList();
						
					synchronized(c) {
						detectionPathClassList.stream().forEach(g -> {
							cMeasList.put("cna:"+g.toString(), 0.0);
						});
					}
						
				
						
//					connected_objects.parallelStream().forEach(d -> {
					for(PathObject d: connected_objects) {
						
						
								
						detectionPathClassList.stream().forEach(g -> {
							if(d.getPathClass().toString() == g.toString()) {
								cMeasList.put("cna:"+g.toString(), 1.0+cMeasList.get("cna:"+g.toString()));
							}
						});
								
						
//					});
					}
					
				});
//				}
				
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
