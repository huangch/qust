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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//import qupath.fx.dialogs.Dialogs;
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

public class NeighboringCellTypeComposition extends AbstractDetectionPlugin<BufferedImage> {
	private static Logger logger = LoggerFactory.getLogger(NeighboringCellTypeComposition.class);
	
	private ParameterList params;
	private String lastResults = null;
	
	/**
	 * Constructor.
	 */
	public NeighboringCellTypeComposition() {
		params = new ParameterList()
			.addTitleParameter("Neighboring Cell Type Composition - Analyzing the composition of the neighboring cells")
			.addIntParameter("layers", "No. of layers", 1, null, "Now many layers that the algorithm has to reach")
			.addBooleanParameter("normalize", "Normalize?", true, "Normalize the output (cell type count divided by the size of neighborhood)")
			;
	}
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		@Override

		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
		   PathObjectHierarchy hierarchy = imageData.getHierarchy();
		   try {
		       // Cache parameter values
		       int layers = params.getIntParameterValue("layers");
		       boolean normalize = params.getBooleanParameterValue("normalize");
		       // Validate connections
		       PathObjectConnections connections = (PathObjectConnections) imageData.getProperty("OBJECT_CONNECTIONS");
		       if(connections == null) {
		           throw new Exception("Connections generated using Delaunay clustering are required.");
		       }
		       // Get selected annotations efficiently
		       List<PathObject> selectedAnnotationPathObjectList = hierarchy
		           .getSelectionModel()
		           .getSelectedObjects()
		           .stream()
		           .filter(e -> e.isAnnotation() && e.hasChildObjects())
		           .collect(Collectors.toList());
		       if(selectedAnnotationPathObjectList.isEmpty()) {
		           throw new Exception("Missed selected annotations");
		       }
		       // Collect all detection objects efficiently using flatMap
		       List<PathObject> allDetectionPathObjectList = selectedAnnotationPathObjectList
		           .parallelStream()
		           .flatMap(p -> p.getChildObjects().stream())
		           .filter(PathObject::isDetection)
		           .collect(Collectors.toList());
		       // Extract unique path classes efficiently
		       Set<PathClass> detectionPathClassSet = allDetectionPathObjectList
		           .parallelStream()
		           .map(PathObject::getPathClass)
		           .filter(Objects::nonNull)  // Handle null classes
		           .collect(Collectors.toSet());
		       // Convert to list for indexed access and create string lookup
		       List<PathClass> pathClassList = new ArrayList<>(detectionPathClassSet);
		       Map<PathClass, String> classToStringMap = pathClassList.stream()
		           .collect(Collectors.toMap(
		               pathClass -> pathClass,
		               pathClass -> pathClass.toString(),
		               (existing, replacement) -> existing  // Handle duplicates
		           ));
		       // MAJOR OPTIMIZATION: Pre-compute neighborhood maps for all cells
		       // This eliminates redundant BFS traversals
		       Map<PathObject, Set<PathObject>> neighborhoodCache = new ConcurrentHashMap<>();
		       // Parallel computation of neighborhoods
		       allDetectionPathObjectList.parallelStream().forEach(cell -> {
		           Set<PathObject> neighborhood = computeNeighborhood(cell, connections, layers);
		           neighborhoodCache.put(cell, neighborhood);
		       });
		       // Process measurements in parallel using the pre-computed neighborhoods
		       allDetectionPathObjectList.parallelStream().forEach(cell -> {
		           Set<PathObject> connectedObjects = neighborhoodCache.get(cell);
		           // Count cell types in neighborhood
		           Map<PathClass, Integer> typeCounts = new HashMap<>();
		           // Initialize counts (including self-count)
		           PathClass cellClass = cell.getPathClass();
		           if (cellClass != null) {
		               typeCounts.put(cellClass, 1);  // Count the cell itself
		           }
		           // Count neighbors by type
		           for (PathObject neighbor : connectedObjects) {
		               PathClass neighborClass = neighbor.getPathClass();
		               if (neighborClass != null) {
		                   typeCounts.merge(neighborClass, 1, Integer::sum);
		               }
		           }
		           // Update measurements atomically
		           MeasurementList measurementList = cell.getMeasurementList();
		           synchronized(measurementList) {
		               // Set counts for all possible classes
		               for (PathClass pathClass : pathClassList) {
		                   String className = classToStringMap.get(pathClass);
		                   int count = typeCounts.getOrDefault(pathClass, 0);
		                   measurementList.put("sctc:count:" + className, (double) count);
		               }
		               // Calculate probabilities if normalization is enabled
		               if (normalize && !connectedObjects.isEmpty()) {
		                   double totalNeighbors = connectedObjects.size();
		                   for (PathClass pathClass : pathClassList) {
		                       String className = classToStringMap.get(pathClass);
		                       double count = typeCounts.getOrDefault(pathClass, 0);
		                       double probability = count / totalNeighbors;
		                       measurementList.put("sctc:prob:" + className, probability);
		                   }
		               }
		           }
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
		/**
		* Optimized BFS to find all neighbors within specified layers
		* Uses iterative approach with sets to avoid redundant traversals
		*/
		private Set<PathObject> computeNeighborhood(PathObject startCell, PathObjectConnections connections, int layers) {
		   Set<PathObject> visited = new HashSet<>();
		   Set<PathObject> currentLayer = new HashSet<>();
		   Set<PathObject> allNeighbors = new HashSet<>();
		   // Start with the cell itself (excluded from final result)
		   currentLayer.add(startCell);
		   visited.add(startCell);
		   // Perform BFS for specified number of layers
		   for (int layer = 0; layer < layers; layer++) {
		       Set<PathObject> nextLayer = new HashSet<>();
		       for (PathObject cell : currentLayer) {
		           List<PathObject> immediateNeighbors = connections.getConnections(cell);
		           for (PathObject neighbor : immediateNeighbors) {
		               if (!visited.contains(neighbor)) {
		                   visited.add(neighbor);
		                   nextLayer.add(neighbor);
		                   allNeighbors.add(neighbor);  // Don't include the starting cell
		               }
		           }
		       }
		       currentLayer = nextLayer;
		       // Early termination if no more neighbors found
		       if (currentLayer.isEmpty()) {
		           break;
		       }
		   }
		   return allNeighbors;
		}
		/**
		* Alternative optimization for very large datasets:
		* Spatial partitioning approach for even better performance
		*/
		private Map<PathObject, Set<PathObject>> computeNeighborhoodsWithSpatialOptimization(
		       List<PathObject> allCells,
		       PathObjectConnections connections,
		       int layers) {
		   // This could be implemented for very large datasets
		   // using spatial data structures like quad-trees or spatial hashing
		   // to avoid checking connections for distant cells
		   Map<PathObject, Set<PathObject>> result = new ConcurrentHashMap<>();
		   allCells.parallelStream().forEach(cell -> {
		       Set<PathObject> neighborhood = computeNeighborhood(cell, connections, layers);
		       result.put(cell, neighborhood);
		   });
		   return result;
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
		return "Neighboring Cell Type Composition";
	}

	@Override
	public String getLastResultsDescription() {
		return lastResults;
	}


	@Override
	public String getDescription() {
		return "Analyzing the composition of the neighboring cells";
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
