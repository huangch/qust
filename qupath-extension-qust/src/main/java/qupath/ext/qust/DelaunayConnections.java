/*-
 * #%L
 * QuST is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * QuST is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QuST.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.ext.qust;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import qupath.lib.analysis.DelaunayTools;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.DefaultPathObjectConnectionGroup;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectConnectionGroup;
import qupath.lib.objects.PathObjectConnections;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractDetectionPlugin;
import qupath.lib.plugins.DetectionPluginTools;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.roi.interfaces.ROI;

/**
 * Connects every cell of the selected annotations to its neighbours in the Delaunay triangulation of the cell
 * centroids, optionally dropping the links that are too long or that join cells of different classes. The connections
 * are stored in the image data, where the neighbour-based analyses of QuST (and the <em>Show connections</em> overlay of
 * QuPath) find them. It replaces QuPath's deprecated <em>Delaunay cluster features 2D</em>.
 *
 * @author Chao Hui Huang
 */
public class DelaunayConnections extends AbstractDetectionPlugin<BufferedImage> {

	private static final Logger logger = LoggerFactory.getLogger(DelaunayConnections.class);

	private static final DoubleProperty maxDistanceProp = PathPrefs.createPersistentPreference("delaunayMaxDistance", 0.0);
	private static final BooleanProperty sameClassProp = PathPrefs.createPersistentPreference("delaunaySameClass", false);

	private final ParameterList params;
	private String lastResults = null;

	public DelaunayConnections() {
		params = new ParameterList()
				.addTitleParameter("Delaunay connections between the cells")
				.addDoubleParameter("maxDistance", "Distance threshold (0: none)", maxDistanceProp.get(), "µm (px if the pixel size is unknown)",
						"Links between cell centroids that are longer than this are dropped, e.g. across gaps in the tissue")
				.addBooleanParameter("sameClass", "Limit links to cells of the same class", sameClassProp.get(),
						"Drop the links between cells of different classes");
	}

	/** A connection group of an explicit neighbour map. */
	private record NeighborGroup(Map<PathObject, List<PathObject>> neighbors) implements PathObjectConnectionGroup {
		@Override
		public boolean containsObject(PathObject pathObject) {
			return neighbors.containsKey(pathObject);
		}

		@Override
		public Collection<PathObject> getPathObjects() {
			return neighbors.keySet();
		}

		@Override
		public List<PathObject> getConnectedObjects(PathObject pathObject) {
			return neighbors.getOrDefault(pathObject, Collections.emptyList());
		}
	}

	class Connector implements ObjectDetector<BufferedImage> {

		@Override
		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
			double maxDistance = params.getDoubleParameterValue("maxDistance");
			boolean sameClass = params.getBooleanParameterValue("sameClass");
			maxDistanceProp.set(maxDistance);
			sameClassProp.set(sameClass);

			PathObjectHierarchy hierarchy = imageData.getHierarchy();
			try {
				List<PathObject> annotations = hierarchy.getSelectionModel().getSelectedObjects().stream()
						.filter(e -> e.isAnnotation() && e.hasChildObjects()).collect(Collectors.toList());
				if (annotations.isEmpty())
					throw new Exception("Missed selected annotations");

				PixelCalibration calibration = imageData.getServer().getPixelCalibration();
				double maxDistancePixels = maxDistance;
				if (calibration.hasPixelSizeMicrons())
					maxDistancePixels = maxDistance / calibration.getAveragedPixelSizeMicrons();

				BiPredicate<PathObject, PathObject> filter = null;
				if (maxDistance > 0)
					filter = DelaunayTools.centroidDistancePredicate(maxDistancePixels, true);
				if (sameClass) {
					BiPredicate<PathObject, PathObject> same = DelaunayTools.sameClassificationPredicate();
					filter = filter == null ? same : filter.and(same);
				}

				// One triangulation per annotation: the cells of separate pieces of tissue are not linked across the gap between them.
				// The centroid of the nucleus is used where there is one, like QuPath's own connections and object hierarchy do.
				Map<PathObject, List<PathObject>> neighbors = new LinkedHashMap<>();
				int cellCount = 0;
				for (PathObject annotation : annotations) {
					List<PathObject> cells = annotation.getChildObjects().stream().filter(PathObject::isDetection).collect(Collectors.toList());
					if (cells.size() < 3) {
						logger.warn("{} has fewer than three cells and is skipped", annotation);
						continue;
					}
					neighbors.putAll(DelaunayTools.createFromCentroids(cells, true).getFilteredNeighbors(filter));
					cellCount += cells.size();
				}
				if (cellCount < 3)
					throw new Exception("At least three cells are needed");

				PathObjectConnections connections = new PathObjectConnections();
				connections.addGroup(new DefaultPathObjectConnectionGroup(new NeighborGroup(neighbors)));
				imageData.setProperty(DefaultPathObjectConnectionGroup.KEY_OBJECT_CONNECTIONS, connections);

				long links = neighbors.values().stream().mapToLong(List::size).sum() / 2;
				lastResults = cellCount + " cells connected by " + links + " links";
				logger.info(lastResults);
			} catch (Exception e) {
				lastResults = e.getMessage();
				logger.error(lastResults);
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
		return "Delaunay connections";
	}

	@Override
	public String getLastResultsDescription() {
		return lastResults;
	}

	@Override
	public String getDescription() {
		return "Connect the cells of the selected annotations to their neighbours in the Delaunay triangulation";
	}

	@Override
	protected void addRunnableTasks(ImageData<BufferedImage> imageData, PathObject parentObject, List<Runnable> tasks) {
		tasks.add(DetectionPluginTools.createRunnableTask(new Connector(), getParameterList(imageData), imageData, parentObject));
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
		return Arrays.asList(PathAnnotationObject.class, TMACoreObject.class);
	}
}
