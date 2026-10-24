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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.StringProperty;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
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
 * Spatial density of the cells of chosen classes, estimated with a Gaussian kernel: every cell of the selected
 * annotations receives the measurement <code>csd:&lt;id&gt;</code>, the kernel density of the chosen cells at its
 * position. Comparing the maps of two cell types shows where they are enriched relative to each other.
 *
 * @author Chao Hui Huang
 */
public class CellSpatialDensity extends AbstractDetectionPlugin<BufferedImage> {
	private static final Logger logger = LoggerFactory.getLogger(CellSpatialDensity.class);

	private static final StringProperty csdTgtClsProp = PathPrefs.createPersistentPreference("csdEstTgtCls", "");
	private static final StringProperty csdIdProp = PathPrefs.createPersistentPreference("csdEstId", "default");
	private static final DoubleProperty csdBandwidthProp = PathPrefs.createPersistentPreference("csdEstBandwidth", 50.0);

	private static final List<String> profilingTypeList = List.of("detection", "annotation");

	private final ParameterList params;
	private String lastResults = null;

	public CellSpatialDensity() {
		PathObjectHierarchy hierarchy = QP.getCurrentImageData().getHierarchy();
		var selected = hierarchy.getSelectionModel().getSelectedObjects();

		// annotation classes are profiled when annotations (and no cells) are selected
		boolean annotationsSelected = selected.stream().anyMatch(PathObject::isAnnotation) && selected.stream().noneMatch(PathObject::isDetection);
		String chosenClasses = selected.stream().map(PathObject::getPathClass).filter(c -> c != null).map(PathClass::toString).distinct()
				.collect(Collectors.joining(","));

		params = new ParameterList()
				.addTitleParameter("Cell Spatial Density (kernel density estimation)")
				.addStringParameter("tgtCls", "Class(es) of the cells", chosenClasses.isEmpty() ? csdTgtClsProp.get() : chosenClasses,
						"Comma-separated classes whose density is estimated")
				.addChoiceParameter("type", "Class taken from", profilingTypeList.get(annotationsSelected ? 1 : 0), profilingTypeList,
						"detection: the class of the cell; annotation: the class of the annotation containing the cell")
				.addDoubleParameter("bandwidth", "Bandwidth", csdBandwidthProp.get(), null,
						"Standard deviation of the Gaussian kernel, in pixels")
				.addStringParameter("id", "ID", csdIdProp.get(), "Name of the measurement (csd:<ID>)");
	}

	class SpatialDensityEstimation implements ObjectDetector<BufferedImage> {

		@Override
		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
			String tgtCls = params.getStringParameterValue("tgtCls");
			String type = (String) params.getChoiceParameterValue("type");
			double bandwidth = params.getDoubleParameterValue("bandwidth");
			String id = params.getStringParameterValue("id");
			csdTgtClsProp.set(tgtCls);
			csdBandwidthProp.set(bandwidth);
			csdIdProp.set(id);

			String measurementKey = "csd:" + id;
			double bw2Inv = 1.0 / (2 * bandwidth * bandwidth);
			// beyond five standard deviations the contribution is negligible
			double maxDistanceSquared = 25 * bandwidth * bandwidth;
			boolean byDetection = type.equals(profilingTypeList.get(0));

			PathObjectHierarchy hierarchy = imageData.getHierarchy();
			try {
				List<PathObject> annotations = hierarchy.getSelectionModel().getSelectedObjects().stream()
						.filter(e -> e.isAnnotation() && e.hasChildObjects()).collect(Collectors.toList());
				if (annotations.isEmpty())
					throw new Exception("Missed selected annotations");

				List<PathObject> cells = annotations.stream().flatMap(p -> p.getChildObjects().stream()).filter(PathObject::isDetection)
						.collect(Collectors.toList());

				Set<String> tgtClsSet = Arrays.stream(tgtCls.split(",")).map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toCollection(HashSet::new));

				List<PathObject> kernelCells = cells.parallelStream().filter(c -> {
					PathClass pathClass = byDetection ? c.getPathClass() : (c.getParent().isAnnotation() ? c.getParent().getPathClass() : null);
					return pathClass != null && tgtClsSet.contains(pathClass.toString().strip());
				}).collect(Collectors.toList());

				int n = kernelCells.size();
				double[] xData = new double[n];
				double[] yData = new double[n];
				IntStream.range(0, n).forEach(i -> {
					ROI roi = kernelCells.get(i).getROI();
					xData[i] = roi.getCentroidX();
					yData[i] = roi.getCentroidY();
				});

				cells.parallelStream().forEach(c -> {
					double xq = c.getROI().getCentroidX();
					double yq = c.getROI().getCentroidY();
					double sum = 0.0;
					for (int i = 0; i < n; i++) {
						double dx = xq - xData[i];
						double dy = yq - yData[i];
						double distanceSquared = dx * dx + dy * dy;
						if (distanceSquared <= maxDistanceSquared)
							sum += Math.exp(-distanceSquared * bw2Inv);
					}
					c.getMeasurementList().put(measurementKey, sum);
				});

				hierarchy.getSelectionModel().setSelectedObject(null);
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
		return "Cell Spatial Density";
	}

	@Override
	public String getLastResultsDescription() {
		return lastResults;
	}

	@Override
	public String getDescription() {
		return "Kernel density estimation of the cells of chosen classes";
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
		return Arrays.asList(PathAnnotationObject.class, TMACoreObject.class);
	}
}
