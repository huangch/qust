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
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.images.ImageData;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.plugins.AbstractInteractivePlugin;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.scripting.QP;

/**
 * Nucleus (or cell) detection with Cellpose inside the selected annotations: an alternative to
 * {@link StarDistCellNucleusDetection} for the first step of the workflow. It runs the
 * <a href="https://github.com/BIOP/qupath-extension-cellpose">BIOP Cellpose extension</a>, which has to be installed
 * separately (it is called by reflection, so QuST does not depend on it, and works with its versions for QuPath 0.5
 * and for QuPath 0.7 and later). The Python environment with Cellpose is the one set in the preferences of that extension.
 *
 * @author Chao Hui Huang
 */
public class CellposeCellNucleusDetection extends AbstractInteractivePlugin<BufferedImage> {

	private static final Logger logger = LoggerFactory.getLogger(CellposeCellNucleusDetection.class);
	private static final String CELLPOSE = "qupath.ext.biop.cellpose.Cellpose2D";

	private final ParameterList params;

	public CellposeCellNucleusDetection() {
		params = new ParameterList()
				.addTitleParameter("Model")
				.addChoiceParameter("cellposeModel", "Cellpose model", "cyto3", List.of("cyto3", "nuclei", "cyto2", "cyto", "cpsam"),
						"Built-in Cellpose model; cpsam is Cellpose-SAM (Cellpose 4)")
				.addStringParameter("customModel", "Custom model (path; empty: use the model above)", "")
				.addTitleParameter("General parameters")
				.addStringParameter("channel", "Detection channel (e.g. DAPI; empty: not set)", "")
				.addDoubleParameter("diameter", "Cell diameter in µm (0: not set)", 0, null, "Expected diameter of the objects, in µm")
				.addDoubleParameter("cellprobThreshold", "Cell probability threshold", 0.0, null, "Cellpose cellprob_threshold")
				.addDoubleParameter("flowThreshold", "Flow threshold", 0.4, null, "Cellpose flow_threshold")
				.addDoubleParameter("normalizePercentilesLow", "Percentile normalization (lower bound)", 1, null, "Percentile normalization (lower bound)")
				.addDoubleParameter("normalizePercentilesHigh", "Percentile normalization (upper bound)", 99, null, "Percentile normalization (upper bound)")
				.addBooleanParameter("useGPU", "Use the GPU (default: true)", true, "Run Cellpose on the GPU")
				.addTitleParameter("Measurements")
				.addBooleanParameter("measureShape", "Add shape measurements (default: false)", false, "Add shape measurements")
				.addBooleanParameter("measureIntensity", "Add intensity measurements (default: false)", false, "Add intensity measurements")
				.addTitleParameter("Additional parameters")
				.addDoubleParameter("cellExpansion", "Cell expansion around the nucleus in µm (-1: nuclei only)", -1, null, "Approximate cells by expanding the nuclei")
				.addDoubleParameter("simplify", "Simplify the shapes by this distance in pixels (0: off)", 1, null, "Simplify the shapes")
				.addIntParameter("tileSize", "Tile size (0: Cellpose extension default)", 0, null, "Tile size of the Cellpose extension");
	}

	@Override
	public boolean runPlugin(TaskRunner taskRunner, ImageData<BufferedImage> imageData, String arg) {
		boolean success = super.runPlugin(taskRunner, imageData, arg);
		imageData.getHierarchy().fireHierarchyChangedEvent(this);
		return success;
	}

	@Override
	protected void addRunnableTasks(ImageData<BufferedImage> imageData, PathObject parentObject, List<Runnable> tasks) {
		ParameterList parameters = getParameterList(imageData);
		tasks.add(() -> {
			try {
				if (parentObject instanceof PathAnnotationObject)
					detect(imageData, parameters, List.of(parentObject));
			} catch (Throwable e) {
				logger.error("Cellpose detection failed in " + parentObject, e);
			} finally {
				parentObject.getMeasurementList().close();
			}
		});
	}

	/** Runs Cellpose on the parent objects. */
	static void detect(ImageData<BufferedImage> imageData, ParameterList params, Collection<PathObject> parents) throws Exception {
		if (QP.getProject() == null)
			throw new IOException("The Cellpose extension keeps its temporary files in the QuPath project folder: "
					+ "open the image from a QuPath project (File > Project...) and run the command again.");
		Object cellpose = build(params, imageData.getServer().getPixelCalibration().getAveragedPixelSizeMicrons());
		call(cellpose, "detectObjects", new Class<?>[] { ImageData.class, Collection.class }, imageData, parents);
	}

	/** Creates the Cellpose2D detector of the parameters. */
	static Object build(ParameterList params, double pixelSizeMicrons) throws Exception {
		Class<?> cellposeClass;
		try {
			cellposeClass = Class.forName(CELLPOSE);
		} catch (ClassNotFoundException e) {
			throw new IOException("The QuPath Cellpose extension (BIOP) is not installed.", e);
		}
		String custom = params.getStringParameterValue("customModel");
		String model = custom.isBlank() ? (String) params.getChoiceParameterValue("cellposeModel") : custom.strip();

		try {
			Object builder = cellposeClass.getMethod("builder", String.class).invoke(null, model);
			builder = call(builder, "pixelSize", new Class<?>[] { double.class }, pixelSizeMicrons);
			builder = call(builder, "cellprobThreshold", new Class<?>[] { Double.class }, params.getDoubleParameterValue("cellprobThreshold"));
			builder = call(builder, "flowThreshold", new Class<?>[] { Double.class }, params.getDoubleParameterValue("flowThreshold"));
			builder = call(builder, "normalizePercentiles", new Class<?>[] { double.class, double.class },
					params.getDoubleParameterValue("normalizePercentilesLow"), params.getDoubleParameterValue("normalizePercentilesHigh"));
			builder = call(builder, "useGPU", new Class<?>[] { boolean.class }, params.getBooleanParameterValue("useGPU"));
			builder = call(builder, "simplify", new Class<?>[] { double.class }, params.getDoubleParameterValue("simplify"));

			String channel = params.getStringParameterValue("channel");
			if (!channel.isBlank())
				builder = call(builder, "channels", new Class<?>[] { String[].class }, (Object) new String[] { channel.strip() });
			if (params.getDoubleParameterValue("diameter") > 0)
				builder = call(builder, "diameter", new Class<?>[] { Double.class }, params.getDoubleParameterValue("diameter"));
			if (params.getDoubleParameterValue("cellExpansion") > 0)
				builder = call(builder, "cellExpansion", new Class<?>[] { double.class }, params.getDoubleParameterValue("cellExpansion"));
			if (params.getBooleanParameterValue("measureShape"))
				builder = call(builder, "measureShape", new Class<?>[0]);
			if (params.getBooleanParameterValue("measureIntensity"))
				builder = call(builder, "measureIntensity", new Class<?>[0]);
			if (params.getIntParameterValue("tileSize") > 0)
				builder = call(builder, "tileSize", new Class<?>[] { int.class }, params.getIntParameterValue("tileSize"));
			return call(builder, "build", new Class<?>[0]);
		} catch (InvocationTargetException e) {
			throw e.getCause() instanceof Exception cause ? cause : e;
		}
	}

	private static Object call(Object target, String method, Class<?>[] types, Object... args) throws Exception {
		try {
			return target.getClass().getMethod(method, types).invoke(target, args);
		} catch (InvocationTargetException e) {
			throw e.getCause() instanceof Exception cause ? cause : e;
		}
	}

	@Override
	public ParameterList getDefaultParameterList(ImageData<BufferedImage> imageData) {
		return params;
	}

	@Override
	public String getName() {
		return "Cellpose nucleus detection";
	}

	@Override
	public String getLastResultsDescription() {
		return "";
	}

	@Override
	public String getDescription() {
		return "Detect the nuclei (and optionally the cells) of the selected annotations with Cellpose";
	}

	@Override
	protected Collection<PathObject> getParentObjects(ImageData<BufferedImage> imageData) {
		Collection<Class<? extends PathObject>> parentClasses = getSupportedParentObjectClasses();
		List<PathObject> parents = new ArrayList<>();
		for (PathObject parent : imageData.getHierarchy().getSelectionModel().getSelectedObjects()) {
			for (Class<? extends PathObject> cls : parentClasses) {
				if (cls.isAssignableFrom(parent.getClass())) {
					parents.add(parent);
					break;
				}
			}
		}
		return parents;
	}

	@Override
	public Collection<Class<? extends PathObject>> getSupportedParentObjectClasses() {
		return List.of(TMACoreObject.class, PathAnnotationObject.class);
	}
}
