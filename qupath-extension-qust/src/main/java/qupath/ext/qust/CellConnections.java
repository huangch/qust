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
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.analysis.DelaunayTools;
import qupath.lib.images.ImageData;
import qupath.lib.objects.DefaultPathObjectConnectionGroup;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectConnections;

/**
 * The neighbours of the cells, for the analyses that work on the neighbourhood of a cell.
 * <p>
 * If the connections of QuST's <em>Delaunay Connections</em> command (or QuPath's deprecated <em>Delaunay cluster features
 * 2D</em>) are stored in the image data, they are used, so that a distance threshold or a restriction to cells of the same
 * class chosen there is respected. Otherwise the Delaunay triangulation of the cells of each annotation is used (the nucleus
 * centroid where there is one, as in the object hierarchy of QuPath), and no command has to be run first.
 *
 * @author Chao Hui Huang
 */
final class CellConnections {

	private static final Logger logger = LoggerFactory.getLogger(CellConnections.class);

	private final PathObjectConnections stored;
	private final Map<PathObject, Map<PathObject, List<PathObject>>> neighbors = new java.util.concurrent.ConcurrentHashMap<>();

	private CellConnections(PathObjectConnections stored) {
		this.stored = stored;
	}

	/** The connections of the image data. */
	static CellConnections of(ImageData<BufferedImage> imageData) {
		Object property = imageData.getProperty(DefaultPathObjectConnectionGroup.KEY_OBJECT_CONNECTIONS);
		if (property instanceof PathObjectConnections connections && !connections.isEmpty()) {
			logger.info("Using the connections stored by the Delaunay Connections command");
			return new CellConnections(connections);
		}
		logger.info("No stored connections: using the Delaunay triangulation of the cells of each annotation");
		return new CellConnections(null);
	}

	/**
	 * The cells next to a cell: the neighbours in the triangulation of the cells of the same annotation (so that the cells of
	 * separate pieces of tissue are not linked across the gap between them), by the centroid of the nucleus where there is one.
	 */
	List<PathObject> getConnections(PathObject cell) {
		if (stored != null)
			return stored.getConnections(cell);
		PathObject annotation = cell.getParent();
		if (annotation == null)
			return List.of();
		Map<PathObject, List<PathObject>> map = neighbors.computeIfAbsent(annotation,
				key -> DelaunayTools.createFromCentroids(key.getChildObjects().stream().filter(PathObject::isDetection).toList(), true).getAllNeighbors());
		return map.getOrDefault(cell, List.of());
	}
}
