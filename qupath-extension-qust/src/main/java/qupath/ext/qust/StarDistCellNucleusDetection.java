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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.images.ImageData;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.plugins.AbstractInteractivePlugin;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.parameters.ParameterList;

/**
 * Nucleus detection with StarDist inside the selected annotations: the first step of the workflow, which gives the
 * cells that the ST data is attached to. It runs the <a href="https://github.com/qupath/qupath-extension-stardist">
 * QuPath StarDist extension</a>, which has to be installed separately (it is called by reflection, so QuST does not
 * depend on it), with the models (<code>.pb</code>) of the "StarDist model directory" preference.
 *
 * @author Chao Hui Huang
 */
public class StarDistCellNucleusDetection extends AbstractInteractivePlugin<BufferedImage> {

	private static final Logger logger = LoggerFactory.getLogger(StarDistCellNucleusDetection.class);
	private static final String STARDIST = "qupath.ext.stardist.StarDist2D";

	private final ParameterList params;

	public StarDistCellNucleusDetection() {
		List<String> models = List.of();
		String dir = QuSTSetup.getInstance().getStardistModelLocationPath();
		if (dir != null && !dir.isBlank() && Files.isDirectory(Paths.get(dir))) {
			try (Stream<Path> files = Files.list(Paths.get(dir))) {
				models = files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".pb")).sorted()
						.collect(Collectors.toList());
			} catch (IOException e) {
				logger.error("Unable to list the StarDist models", e);
			}
		}
		if (models.isEmpty())
			logger.warn("No StarDist model (.pb) found in the StarDist model directory '{}' (QuST preferences)", dir);

		params = new ParameterList()
				.addTitleParameter("General parameters")
				.addDoubleParameter("threshold", "Probability (detection) threshold", 0.1, null, "Probability (detection) threshold")
				.addDoubleParameter("normalizePercentilesLow", "Percentile normalization (lower bound)", 1, null, "Percentile normalization (lower bound)")
				.addDoubleParameter("normalizePercentilesHigh", "Percentile normalization (upper bound)", 99, null, "Percentile normalization (upper bound)")
				.addTitleParameter("Measurements")
				.addBooleanParameter("includeProbability", "Add probability as a measurement (default: false)", false, "Enables later filtering")
				.addBooleanParameter("measureShape", "Add shape measurements (default: false)", false, "Add shape measurements")
				.addBooleanParameter("measureIntensity", "Add intensity measurements (default: false)", false, "Add intensity measurements")
				.addTitleParameter("Additional parameters")
				.addChoiceParameter("starDistModel", "Model (.pb file)", models.isEmpty() ? "" : models.get(0), models.isEmpty() ? List.of("") : models,
						"Model of the StarDist model directory")
				.addStringParameter("channel", "Detection channel (e.g. DAPI; empty: not set)", "")
				.addDoubleParameter("cellExpansion", "Cell expansion around the nucleus in µm (-1: nuclei only)", -1, null, "Approximate cells by expanding the nuclei")
				.addDoubleParameter("cellConstrainScale", "Constrain the cell expansion by the nucleus size (-1: off)", -1, null, "Constrain the cell expansion by the nucleus size")
				.addIntParameter("nThreads", "Threads (0: StarDist default)", 0, null, "Number of threads of StarDist")
				.addIntParameter("tileSize", "Tile size (0: StarDist default)", 0, null, "Tile size of StarDist");
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
				if (hasCause(e, UnsatisfiedLinkError.class))
					logger.error("StarDist needs the OpenCV libraries of QuPath, which could not be loaded on this computer. "
							+ "QuPath's OpenCV natives need a recent glibc (2.34 or later on Linux); see the QuST documentation.", e);
				else
					logger.error("StarDist detection failed in " + parentObject, e);
			} finally {
				parentObject.getMeasurementList().close();
			}
		});
	}

	private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
		for (Throwable t = e; t != null; t = t.getCause())
			if (type.isInstance(t))
				return true;
		return false;
	}

	/** Runs StarDist on the parent objects. */
	static void detect(ImageData<BufferedImage> imageData, ParameterList params, Collection<PathObject> parents) throws Exception {
		Object stardist = build(params, imageData.getServer().getPixelCalibration().getAveragedPixelSizeMicrons());
		stardist.getClass().getMethod("detectObjects", ImageData.class, Collection.class).invoke(stardist, imageData, parents);
	}

	/** Creates the StarDist2D detector of the parameters. */
	static Object build(ParameterList params, double pixelSizeMicrons) throws Exception {
		Class<?> stardistClass;
		try {
			stardistClass = Class.forName(STARDIST);
		} catch (ClassNotFoundException e) {
			throw new IOException("The QuPath StarDist extension is not installed.", e);
		}
		String model = (String) params.getChoiceParameterValue("starDistModel");
		if (model == null || model.isBlank())
			throw new IOException("No StarDist model (.pb) is available in the StarDist model directory of the QuST preferences.");
		String modelPath = Paths.get(QuSTSetup.getInstance().getStardistModelLocationPath(), model).toString();

		try {
			Object builder = stardistClass.getMethod("builder", String.class).invoke(null, modelPath);
			builder = call(builder, "threshold", new Class<?>[] { double.class }, params.getDoubleParameterValue("threshold"));
			builder = call(builder, "normalizePercentiles", new Class<?>[] { double.class, double.class },
					params.getDoubleParameterValue("normalizePercentilesLow"), params.getDoubleParameterValue("normalizePercentilesHigh"));
			builder = call(builder, "pixelSize", new Class<?>[] { double.class }, pixelSizeMicrons);

			String channel = params.getStringParameterValue("channel");
			if (!channel.isBlank())
				builder = call(builder, "channels", new Class<?>[] { String[].class }, (Object) new String[] { channel });
			if (params.getDoubleParameterValue("cellExpansion") > 0)
				builder = call(builder, "cellExpansion", new Class<?>[] { double.class }, params.getDoubleParameterValue("cellExpansion"));
			if (params.getDoubleParameterValue("cellConstrainScale") > 0)
				builder = call(builder, "cellConstrainScale", new Class<?>[] { double.class }, params.getDoubleParameterValue("cellConstrainScale"));
			if (params.getBooleanParameterValue("measureShape"))
				builder = call(builder, "measureShape", new Class<?>[0]);
			if (params.getBooleanParameterValue("measureIntensity"))
				builder = call(builder, "measureIntensity", new Class<?>[0]);
			if (params.getBooleanParameterValue("includeProbability"))
				builder = call(builder, "includeProbability", new Class<?>[] { boolean.class }, true);
			if (params.getIntParameterValue("nThreads") > 0)
				builder = call(builder, "nThreads", new Class<?>[] { int.class }, params.getIntParameterValue("nThreads"));
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
		return "StarDist nucleus detection";
	}

	@Override
	public String getLastResultsDescription() {
		return "";
	}

	@Override
	public String getDescription() {
		return "Detect the nuclei (and optionally the cells) of the selected annotations with StarDist";
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
