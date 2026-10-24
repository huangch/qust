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
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.beans.property.StringProperty;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathRootObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractDetectionPlugin;
import qupath.lib.plugins.DetectionPluginTools;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.roi.interfaces.ROI;

/**
 * ST2WSI Registration: registers the DAPI image of a spatial transcriptomics slide (10x Xenium) to the H&amp;E image that is open, and
 * writes the registration files that the Xenium loader reads into the Xenium directory. It is the ST2WSI Registration plugin of Fiji
 * with the same steps, parameters and results, run inside QuPath (see {@link ST2WSICore}).
 *
 * @author Chao Hui Huang
 */
public class ST2WSIRegistration extends AbstractDetectionPlugin<BufferedImage> {

	private static final Logger logger = LoggerFactory.getLogger(ST2WSIRegistration.class);

	/** The same preference as the Xenium loader: the folder chosen here is the one the loader opens. */
	private final StringProperty xeniumDirProp = PathPrefs.createPersistentPreference("xnumAntnXnumFldr", "");

	private static final List<String> ROTATIONS = List.of("-270", "-180", "-90", "0", "90", "180", "270");
	private static final List<String> SERIES = List.of("1", "2", "3", "4", "5", "6", "7", "8");
	private static final List<String> STAINS = List.of("H&E", "H&E 2");
	private static final List<String> CHANNELS = List.of("Hematoxylon", "Eosin", "Residual");
	private static final List<String> MODES = List.of("Fast", "Accurate", "Mono");

	private ParameterList params;

	private String lastResults = null;

	private boolean cancelled = false;

	/**
	 * Constructor.
	 */
	public ST2WSIRegistration() {}

	/** The parameters, with the defaults and the names of the Fiji plugin. */
	private ParameterList buildParameters(ImageData<BufferedImage> imageData) {
		ST2WSICore.Settings d = new ST2WSICore.Settings();

		List<String> heSeries = List.of("3");
		String heDefault = "3";
		if (imageData != null) {
			ImageServer<BufferedImage> server = imageData.getServer();
			heSeries = ST2WSICore.seriesLabels(server);
			heDefault = heSeries.get(0);
			double best = Double.MAX_VALUE;
			for (int i = 0; i < server.nResolutions(); i++) {
				double distance = Math.abs(Math.log(server.getDownsampleForResolution(i) / 16.0));
				if (distance < best) {
					best = distance;
					heDefault = heSeries.get(i);
				}
			}
		}

		return new ParameterList()
			.addTitleParameter("Spatial transcriptomics image (reference) and the open H&E image (target)")
			.addStringParameter("xeniumDir", "Xenium directory", xeniumDirProp.get(), "The Xenium output folder: the DAPI image is looked for in it, and the registration files are written into it")
			.addStringParameter("dapiImage", "DAPI image file (empty: use the Xenium directory)", "", "The DAPI image of the spatial transcriptomics slide; leave it empty to use the morphology image of the Xenium directory")
			.addChoiceParameter("refSeries", "Reference image series", "5", SERIES, "The series (resolution level, 1 is the full resolution) of the DAPI image that is registered; both images should have about the same resolution (use 'List the series only' to see them)")
			.addChoiceParameter("tgtSeries", "Target image series", heDefault, heSeries, "The series (resolution level) of the open H&E image that is registered")
			.addBooleanParameter("refFlipped", "Reference image needs to be flipped (horizontally)?", d.refFlipped, "Flip the DAPI image horizontally (after the rotation)")
			.addChoiceParameter("refRotated", "Reference image needs to be rotated?", d.refRotated, ROTATIONS, "Rotation of the DAPI image in degrees (to the right) that brings it to the orientation of the H&E image")
			.addDoubleParameter("pxlSz", "Reference image pixel dimension", d.pxlSz, GeneralToolsUnit.MICROMETER, "Pixel size of the full resolution DAPI image")
			.addChoiceParameter("tgtChannel", "Channel of target image to be used?", d.tgtChannel, CHANNELS, "The stain of the H&E image that is registered to the DAPI image (haematoxylon)")
			.addChoiceParameter("tgtStain", "Colour deconvolution vectors", d.tgtStain, STAINS, "Where the haematoxylin does not separate well with one set of vectors, try the other")

			.addTitleParameter("Denoising Parameters")
			.addIntParameter("rolling", "Rolling in Subtract Background", d.rolling, null, "Radius of the rolling ball of the background subtraction")
			.addDoubleParameter("sigma", "sigma in Gaussian Blur", d.sigma, null, "Sigma of the Gaussian blur")

			.addTitleParameter("SIFT Parameters")
			.addDoubleParameter("sift_initialSigma", "Initial_gaussian_blur", d.initialSigma, "px", "Initial Gaussian blur of the SIFT scale space")
			.addIntParameter("sift_steps", "Steps_per_scale_octave", d.steps, null, "Steps per scale octave")
			.addIntParameter("sift_minOctaveSize", "Minimum_image_size", d.minOctaveSize, "px", "Minimum image size of the scale space")
			.addIntParameter("sift_maxOctaveSize", "Maximum_image_size", d.maxOctaveSize, "px", "Maximum image size of the scale space")
			.addIntParameter("sift_fdSize", "Feature_descriptor_size", d.fdSize, null, "Feature descriptor size")
			.addIntParameter("sift_fdBins", "Feature_descriptor_bins", d.fdBins, null, "Feature descriptor orientation bins")
			.addDoubleParameter("rod", "Closest/next_ratio", d.rod, null, "Ratio of the distances of the closest and the next closest match (smaller: stricter)")
			.addDoubleParameter("maxEpsilon", "Max_alignment_error", d.maxEpsilon, "px", "Largest error of a match that RANSAC accepts as an inlier")
			.addDoubleParameter("minInlierRatio", "Min_inlier_ratio", d.minInlierRatio, null, "Smallest share of inliers")
			.addIntParameter("minNumInliers", "Min_num_inliers", d.minNumInliers, null, "Smallest number of inliers")

			.addTitleParameter("bUnwarpJ Parameters")
			.addChoiceParameter("bUnwarpJ_mode", "Registration_mode", MODES.get(d.bUnwarpJMode), MODES, "Registration mode of bUnwarpJ")
			.addIntParameter("img_subsamp_fact", "Image_subsampling", d.imgSubsampFact, null, "Image subsampling factor (a power of 2 exponent)")
			.addIntParameter("min_scale_deformation", "Min_scale_deform", d.minScaleDeformation, null, "Coarsest scale of the deformation: 0 very coarse to 3 fine")
			.addIntParameter("max_scale_deformation", "Max_scale_deform", d.maxScaleDeformation, null, "Finest scale of the deformation: 0 very coarse to 4 super fine")
			.addDoubleParameter("divWeight", "Divergence_weight", d.divWeight, null, "Divergence weight")
			.addDoubleParameter("curlWeight", "Curl_weight", d.curlWeight, null, "Curl weight")
			.addDoubleParameter("landmarkWeight", "Landmark_weight", d.landmarkWeight, null, "Landmark weight")
			.addDoubleParameter("imageWeight", "Image_weight", d.imageWeight, null, "Image weight")
			.addDoubleParameter("consistencyWeight", "Consistency_weight", d.consistencyWeight, null, "Weight of the consistency of the forward and backward deformations")
			.addDoubleParameter("stopThreshold", "Stop_threshold", d.stopThreshold, null, "Stop threshold of the optimization")

			.addTitleParameter("Output Options")
			.addBooleanParameter("verbose", "Verbose (also write the prepared images)", d.verbose, "Also write prepared_dapi.png and prepared_he.png, the images that SIFT sees")
			.addBooleanParameter("listSeries", "List the series only", false, "Only write the series (resolution levels) of the two images into the log and stop");
	}

	/** The number of a series from its label ("3" or "3 (2614 x 2053, downsample 16)"). */
	private static int seriesNumber(Object choice) {
		String text = String.valueOf(choice).strip();
		int space = text.indexOf(' ');
		return Integer.parseInt(space < 0 ? text : text.substring(0, space));
	}

	/** The DAPI image of a Xenium folder: the morphology image of the run. */
	static Path findDapiImage(Path xeniumDir) {
		for (String candidate : new String[] { "morphology_focus/morphology_focus_0000.ome.tif", "morphology_focus/morphology_focus.ome.tif",
				"morphology_focus.ome.tif", "morphology.ome.tif" }) {
			Path path = xeniumDir.resolve(candidate);
			if (Files.isRegularFile(path))
				return path;
		}
		return null;
	}

	class Registration implements ObjectDetector<BufferedImage> {

		@Override
		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) {
			PathObjectHierarchy hierarchy = imageData.getHierarchy();
			ArrayList<PathObject> resultPathObjectList = new ArrayList<>(hierarchy.getRootObject().getChildObjects());
			if (cancelled) {
				lastResults = "Cancelled: the existing registration files were not overwritten.";
				logger.info(lastResults);
				return resultPathObjectList;
			}

			try {
				String xeniumDir = params.getStringParameterValue("xeniumDir").strip();
				if (xeniumDir.isBlank())
					xeniumDir = xeniumDirProp.get();
				if (xeniumDir.isBlank())
					throw new IllegalArgumentException("No Xenium directory is selected");
				Path outputDir = Paths.get(xeniumDir);

				String dapiText = params.getStringParameterValue("dapiImage").strip();
				Path dapiPath = dapiText.isBlank() ? findDapiImage(outputDir) : Paths.get(dapiText);
				if (dapiPath == null || !Files.isRegularFile(dapiPath))
					throw new IllegalArgumentException("No DAPI image: " + (dapiPath == null ? "there is no morphology image in " + outputDir : dapiPath));
				xeniumDirProp.set(xeniumDir);

				try (ImageServer<BufferedImage> dapi = ImageServers.buildServer(dapiPath.toUri().toString())) {
					if (params.getBooleanParameterValue("listSeries")) {
						lastResults = "DAPI image (" + dapiPath + "), " + ST2WSICore.describe(dapi) + "\nH&E image, " + ST2WSICore.describe(imageData.getServer());
						logger.info(lastResults);
						return resultPathObjectList;
					}

					ST2WSICore.Settings s = new ST2WSICore.Settings();
					s.refSeries = seriesNumber(params.getChoiceParameterValue("refSeries"));
					s.tgtSeries = seriesNumber(params.getChoiceParameterValue("tgtSeries"));
					s.refFlipped = params.getBooleanParameterValue("refFlipped");
					s.refRotated = (String) params.getChoiceParameterValue("refRotated");
					s.pxlSz = params.getDoubleParameterValue("pxlSz").floatValue();
					s.tgtChannel = (String) params.getChoiceParameterValue("tgtChannel");
					s.tgtStain = (String) params.getChoiceParameterValue("tgtStain");
					s.rolling = params.getIntParameterValue("rolling");
					s.sigma = params.getDoubleParameterValue("sigma").floatValue();
					s.initialSigma = params.getDoubleParameterValue("sift_initialSigma").floatValue();
					s.steps = params.getIntParameterValue("sift_steps");
					s.minOctaveSize = params.getIntParameterValue("sift_minOctaveSize");
					s.maxOctaveSize = params.getIntParameterValue("sift_maxOctaveSize");
					s.fdSize = params.getIntParameterValue("sift_fdSize");
					s.fdBins = params.getIntParameterValue("sift_fdBins");
					s.rod = params.getDoubleParameterValue("rod").floatValue();
					s.maxEpsilon = params.getDoubleParameterValue("maxEpsilon").floatValue();
					s.minInlierRatio = params.getDoubleParameterValue("minInlierRatio").floatValue();
					s.minNumInliers = params.getIntParameterValue("minNumInliers");
					s.bUnwarpJMode = Math.max(0, MODES.indexOf(params.getChoiceParameterValue("bUnwarpJ_mode")));
					s.imgSubsampFact = params.getIntParameterValue("img_subsamp_fact");
					s.minScaleDeformation = params.getIntParameterValue("min_scale_deformation");
					s.maxScaleDeformation = params.getIntParameterValue("max_scale_deformation");
					s.divWeight = params.getDoubleParameterValue("divWeight");
					s.curlWeight = params.getDoubleParameterValue("curlWeight");
					s.landmarkWeight = params.getDoubleParameterValue("landmarkWeight");
					s.imageWeight = params.getDoubleParameterValue("imageWeight");
					s.consistencyWeight = params.getDoubleParameterValue("consistencyWeight");
					s.stopThreshold = params.getDoubleParameterValue("stopThreshold");
					s.verbose = params.getBooleanParameterValue("verbose");

					lastResults = ST2WSICore.register(dapi, imageData.getServer(), s, outputDir);
					logger.info(lastResults);
				}
			} catch (Exception e) {
				lastResults = "ST2WSI registration failed: " + e.getMessage();
				logger.error(lastResults, e);
			}

			if (Thread.currentThread().isInterrupted()) {
				lastResults = "Interrupted!";
				logger.warn(lastResults);
			}
			return resultPathObjectList;
		}

		@Override
		public String getLastResultsDescription() {
			return lastResults;
		}
	}

	/** Asks, as the Fiji plugin does, whether the registration files that are there may be overwritten (only in the QuPath window). */
	private static boolean confirmOverwrite(Path outputDir) {
		if (QuPathGUI.getInstance() == null)
			return true;
		boolean[] answer = { true };
		Runnable ask = () -> {
			Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Overwrite the existing direct_transf.txt/registration_params.json in " + outputDir + "?",
					ButtonType.OK, ButtonType.CANCEL);
			alert.setTitle("Overwrite?");
			answer[0] = alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
		};
		if (Platform.isFxApplicationThread()) {
			ask.run();
		} else {
			CountDownLatch latch = new CountDownLatch(1);
			Platform.runLater(() -> {
				try {
					ask.run();
				} finally {
					latch.countDown();
				}
			});
			try {
				latch.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return answer[0];
	}

	@Override
	protected void preprocess(TaskRunner taskRunner, ImageData<BufferedImage> imageData) {
		cancelled = false;
		if (params == null)
			return;
		String xeniumDir = params.getStringParameterValue("xeniumDir").strip();
		if (xeniumDir.isBlank()) {
			File dir = qupath.fx.dialogs.FileChoosers.promptForDirectory("Xenium directory", new File(xeniumDirProp.get()));
			if (dir != null) {
				xeniumDirProp.set(dir.toString());
				xeniumDir = dir.toString();
			}
		}
		if (!xeniumDir.isBlank() && !params.getBooleanParameterValue("listSeries")) {
			Path outputDir = Paths.get(xeniumDir);
			if ((Files.exists(outputDir.resolve("direct_transf.txt")) || Files.exists(outputDir.resolve("registration_params.json")))
					&& !confirmOverwrite(outputDir))
				cancelled = true;
		}
	}

	@Override
	public ParameterList getDefaultParameterList(ImageData<BufferedImage> imageData) {
		if (params == null)
			params = buildParameters(imageData);
		return params;
	}

	@Override
	public String getName() {
		return "ST2WSI Registration";
	}

	@Override
	public String getLastResultsDescription() {
		return lastResults;
	}

	@Override
	public String getDescription() {
		return "Register the DAPI image of a spatial transcriptomics slide to the H&E image (SIFT affine, then elastic B-spline), as the ST2WSI Registration plugin of Fiji does.";
	}

	@Override
	protected void addRunnableTasks(ImageData<BufferedImage> imageData, PathObject parentObject, List<Runnable> tasks) {
		tasks.add(DetectionPluginTools.createRunnableTask(new Registration(), getParameterList(imageData), imageData, parentObject));
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
		List<Class<? extends PathObject>> list = new ArrayList<>();
		list.add(TMACoreObject.class);
		list.add(PathRootObject.class);
		return list;
	}

	/** Units of the parameters. */
	private static final class GeneralToolsUnit {
		static final String MICROMETER = qupath.lib.common.GeneralTools.micrometerSymbol();
	}
}
