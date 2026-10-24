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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractDetectionPlugin;
import qupath.lib.plugins.DetectionPluginTools;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.roi.interfaces.ROI;

/**
 * Library-size normalization of the transcript counts: the <code>transcript:&lt;gene&gt;</code> measurements of every
 * cell of the selected annotations are scaled to a common total per cell and, optionally, log-transformed
 * (<code>log(1 + x)</code>). The result is written as <code>transcript-norm:&lt;gene&gt;</code>; the counts are not
 * changed, so tools that expect raw counts (such as the HERaLD extension) keep working.
 *
 * @author Chao Hui Huang
 */
public class TranscriptNormalization extends AbstractDetectionPlugin<BufferedImage> {

	/** Prefix of the raw counts written by the loaders. */
	public static final String RAW_PREFIX = "transcript:";
	/** Prefix of the normalized values written by this command. */
	public static final String PREFIX = "transcript-norm:";

	private static final Logger logger = LoggerFactory.getLogger(TranscriptNormalization.class);

	private static final DoubleProperty targetSumProp = PathPrefs.createPersistentPreference("trNormTargetSum", 100.0);
	private static final BooleanProperty log1pProp = PathPrefs.createPersistentPreference("trNormLog1p", true);
	private static final IntegerProperty minTranscriptsProp = PathPrefs.createPersistentPreference("trNormMinTranscripts", 0);

	private final ParameterList params;
	private String lastResults = null;

	public TranscriptNormalization() {
		params = new ParameterList()
				.addTitleParameter("Normalize transcripts: counts per cell to a common total, then log(1 + x)")
				.addDoubleParameter("targetSum", "Total per cell", targetSumProp.get(), null,
						"Every cell is scaled so that its transcript:<gene> values add up to this number")
				.addBooleanParameter("log1p", "Log transform, log(1 + x)", log1pProp.get(), "Apply log(1 + x) after the scaling")
				.addIntParameter("minTranscripts", "Minimum transcripts per cell", minTranscriptsProp.get(), null,
						"Cells with fewer transcripts get no normalized values (0: all cells). Cells with very few counts are scaled up a lot and are noisy");
	}

	/**
	 * Normalized value of a count.
	 *
	 * @param count the raw count
	 * @param total the total of the cell
	 * @param targetSum the total after scaling
	 * @param log1p whether to apply log(1 + x)
	 */
	static double normalize(double count, double total, double targetSum, boolean log1p) {
		double scaled = count * targetSum / total;
		return log1p ? Math.log1p(scaled) : scaled;
	}

	class Normalizer implements ObjectDetector<BufferedImage> {

		@Override
		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
			double targetSum = params.getDoubleParameterValue("targetSum");
			boolean log1p = params.getBooleanParameterValue("log1p");
			int minTranscripts = params.getIntParameterValue("minTranscripts");
			targetSumProp.set(targetSum);
			log1pProp.set(log1p);
			minTranscriptsProp.set(minTranscripts);

			PathObjectHierarchy hierarchy = imageData.getHierarchy();
			try {
				if (!(targetSum > 0))
					throw new Exception("The total per cell has to be positive");

				List<PathObject> annotations = hierarchy.getSelectionModel().getSelectedObjects().stream()
						.filter(e -> e.isAnnotation() && e.hasChildObjects()).collect(Collectors.toList());
				if (annotations.isEmpty())
					throw new Exception("Missed selected annotations");

				List<PathObject> cells = annotations.stream().flatMap(p -> p.getChildObjects().stream()).filter(PathObject::isDetection)
						.collect(Collectors.toList());

				long normalized = cells.parallelStream().filter(c -> normalizeCell(c, targetSum, log1p, minTranscripts)).count();
				lastResults = normalized + " of " + cells.size() + " cells normalized (" + PREFIX + "<gene>)";
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

	/**
	 * The cells are independent of each other, so they are normalized in parallel. A measurement list looks a name up by searching
	 * its names, so the values are read by position and the earlier results are removed in one call; that keeps the work per cell
	 * from growing with the square of the number of genes as much as the list allows.
	 *
	 * @return whether the cell had enough transcripts to be normalized
	 */
	static boolean normalizeCell(PathObject cell, double targetSum, boolean log1p, int minTranscripts) {
		MeasurementList list = cell.getMeasurementList();

		// earlier results of this command are replaced
		String[] earlier = list.getMeasurementNames().stream().filter(n -> n.startsWith(PREFIX)).toArray(String[]::new);
		if (earlier.length > 0)
			list.removeMeasurements(earlier);

		double total = 0;
		List<String> genes = new ArrayList<>();
		double[] counts = new double[list.size()];
		for (Map.Entry<String, Number> entry : list.asMap().entrySet()) {
			String name = entry.getKey();
			if (!name.startsWith(RAW_PREFIX))
				continue;
			double v = entry.getValue().doubleValue();
			if (Double.isNaN(v))
				continue;
			counts[genes.size()] = v;
			genes.add(name);
			total += v;
		}
		if (genes.isEmpty() || !(total > 0) || total < minTranscripts)
			return false;

		for (int i = 0; i < genes.size(); i++)
			list.put(PREFIX + genes.get(i).substring(RAW_PREFIX.length()), normalize(counts[i], total, targetSum, log1p));
		return true;
	}

	@Override
	public ParameterList getDefaultParameterList(ImageData<BufferedImage> imageData) {
		return params;
	}

	@Override
	public String getName() {
		return "Normalize transcripts";
	}

	@Override
	public String getLastResultsDescription() {
		return lastResults;
	}

	@Override
	public String getDescription() {
		return "Scale the transcript counts of each cell to a common total and log-transform them";
	}

	@Override
	protected void addRunnableTasks(ImageData<BufferedImage> imageData, PathObject parentObject, List<Runnable> tasks) {
		tasks.add(DetectionPluginTools.createRunnableTask(new Normalizer(), getParameterList(imageData), imageData, parentObject));
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
