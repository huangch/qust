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
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.imageio.ImageIO;

import org.imgscalr.Scalr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.StringProperty;
import qupath.fx.dialogs.FileChoosers;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathRootObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractDetectionPlugin;
import qupath.lib.plugins.DetectionPluginTools;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

/**
 * Exports an image patch for each detection object inside the selected annotations. The patches, together with the
 * measurement table of the detections, form the training set of the cell classifier.
 *
 * @author Chao Hui Huang
 */
public class ObjectClassificationImageAcquisition extends AbstractDetectionPlugin<BufferedImage> {

	private static final Logger logger = LoggerFactory.getLogger(ObjectClassificationImageAcquisition.class);

	private static final StringProperty objClsImgAcqDistProp = PathPrefs.createPersistentPreference("objClsImgAcqDist", "");
	private static final DoubleProperty objClsImgAcqMPPProp = PathPrefs.createPersistentPreference("objClsImgAcqMPP", 0.21233);
	private static final IntegerProperty objClsImgAcqSamplingSizeProp = PathPrefs.createPersistentPreference("objClsImgAcqSamplingSize", 36);
	private static final BooleanProperty objClsImgAcqAllSamplesProp = PathPrefs.createPersistentPreference("objClsImgAcqAllSamplesProp", true);
	private static final IntegerProperty objClsImgAcqSamplingNumProp = PathPrefs.createPersistentPreference("objClsImgAcqSamplingNum", 0);
	private static final QuSTSetup qustSetup = QuSTSetup.getInstance();

	private final ParameterList params;
	private String lastResults = null;

	public ObjectClassificationImageAcquisition() {
		params = new ParameterList()
				.addStringParameter("dist", "Destination folder", objClsImgAcqDistProp.get(), "Folder receiving the image patches")
				.addEmptyParameter("").addEmptyParameter("Resampling using...")
				.addBooleanParameter("normalization", "Normalization (default: yes)", true, "Normalize the H&E staining of the patches")
				.addBooleanParameter("dontResampling", "Do not rescale image (default: yes)", true, "Export the patches at the pixel size of the image")
				.addEmptyParameter("...or...")
				.addDoubleParameter("MPP", "pixel size", objClsImgAcqMPPProp.get(), GeneralTools.micrometerSymbol(), "Pixel size")
				.addEmptyParameter("")
				.addIntParameter("samplingSize", "Sampling size", objClsImgAcqSamplingSizeProp.get(), "pixel(s)", "Sampling size")
				.addBooleanParameter("allSamples", "Include all samples (default: yes)", objClsImgAcqAllSamplesProp.get(), "Include all samples?")
				.addEmptyParameter("...or...")
				.addIntParameter("samplingNum", "Maximal sampling number", objClsImgAcqSamplingNumProp.get(), "object(s)", "Maximal sampling number");
	}

	class DetectedObjectImageSampling implements ObjectDetector<BufferedImage> {

		@Override
		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
			PathObjectHierarchy hierarchy = imageData.getHierarchy();

			objClsImgAcqMPPProp.set(params.getDoubleParameterValue("MPP"));
			objClsImgAcqSamplingSizeProp.set(params.getIntParameterValue("samplingSize"));
			objClsImgAcqAllSamplesProp.set(params.getBooleanParameterValue("allSamples"));
			objClsImgAcqSamplingNumProp.set(params.getIntParameterValue("samplingNum"));

			List<PathObject> selectedAnnotations = Collections.synchronizedList(hierarchy.getSelectionModel().getSelectedObjects().stream()
					.filter(e -> e.isAnnotation()).collect(Collectors.toList()));

			if (selectedAnnotations.isEmpty()) {
				logger.error("No annotation is selected");
				return hierarchy.getAnnotationObjects();
			}

			ImageServer<BufferedImage> server = imageData.getServer();
			String serverPath = server.getPath();

			double imageMPP = server.getPixelCalibration().getAveragedPixelSizeMicrons();
			double scalingFactor = params.getBooleanParameterValue("dontResampling") ? 1.0 : params.getDoubleParameterValue("MPP") / imageMPP;
			int samplingFeatureSize = (int) (0.5 + scalingFactor * params.getIntParameterValue("samplingSize"));

			List<PathObject> allPathObjects = Collections.synchronizedList(new ArrayList<PathObject>());
			selectedAnnotations.forEach(p -> allPathObjects.addAll(p.getChildObjects().stream().filter(e -> e.isDetection()).collect(Collectors.toList())));
			Collections.shuffle(allPathObjects);

			int normalizationSampleSize = qustSetup.getNormalizationSampleSize();
			if (allPathObjects.size() < normalizationSampleSize) {
				logger.error("The number of objects is smaller than the number required for normalization");
				return hierarchy.getAnnotationObjects();
			}

			List<PathObject> normalizationSamplingPathObjects = Collections.synchronizedList(allPathObjects.subList(0, normalizationSampleSize));
			List<BufferedImage> normalizationSamplingImageList = Collections.synchronizedList(new ArrayList<>());
			AtomicBoolean success = new AtomicBoolean(true);

			IntStream.range(0, normalizationSamplingPathObjects.size()).parallel().forEach(i -> {
				if (!success.get()) return;

				ROI objRoi = normalizationSamplingPathObjects.get(i).getROI();
				int x0 = (int) (0.5 + objRoi.getCentroidX() - ((double) samplingFeatureSize / 2.0));
				int y0 = (int) (0.5 + objRoi.getCentroidY() - ((double) samplingFeatureSize / 2.0));
				RegionRequest objRegion = RegionRequest.createInstance(serverPath, 1.0, x0, y0, samplingFeatureSize, samplingFeatureSize);

				try {
					BufferedImage imgContent = server.readRegion(objRegion);
					BufferedImage imgBuf = new BufferedImage(imgContent.getWidth(), imgContent.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
					imgBuf.getGraphics().drawImage(imgContent, 0, 0, null);
					normalizationSamplingImageList.add(imgBuf);
				} catch (IOException e) {
					logger.error("Unable to read an image patch", e);
					success.set(false);
				}
			});

			if (!success.get()) {
				logger.error("Collecting the data for computing the normalization failed!");
				return hierarchy.getAnnotationObjects();
			}

			BufferedImage normalizationSamplingImage = MacenkoStainingNormalizer.concatBufferedImages(normalizationSamplingImageList);
			double[][][] normalizationSamplingImageRGBAry = MacenkoStainingNormalizer.convertImageToRGBArray(normalizationSamplingImage);
			double[][][] normalizationSamplingImageSDAAry = MacenkoStainingNormalizer.rgbToSda(normalizationSamplingImageRGBAry, 255, false);
			double[][] est_W = MacenkoStainingNormalizer.separateStainsMacenkoPca(normalizationSamplingImageSDAAry);

			int samplingNum = params.getIntParameterValue("samplingNum") == 0
					|| params.getIntParameterValue("samplingNum") > allPathObjects.size()
					|| params.getBooleanParameterValue("allSamples")
							? allPathObjects.size()
							: params.getIntParameterValue("samplingNum");

			List<PathObject> samplingPathObjects = Collections.synchronizedList(allPathObjects.subList(0, samplingNum));

			Path locationPath = Paths.get(objClsImgAcqDistProp.get());
			String fileFmt = qustSetup.getImageFileFormat().strip();
			String fileExt = fileFmt.charAt(0) == '.' ? fileFmt.substring(1) : fileFmt;

			locationPath.toFile().mkdirs();

			IntStream.range(0, samplingPathObjects.size()).parallel().forEach(i -> {
				if (!success.get()) return;

				PathObject objObject = samplingPathObjects.get(i);
				ROI objRoi = objObject.getROI();

				int x0 = (int) (0.5 + objRoi.getCentroidX() - ((double) samplingFeatureSize / 2.0));
				int y0 = (int) (0.5 + objRoi.getCentroidY() - ((double) samplingFeatureSize / 2.0));
				RegionRequest objRegion = RegionRequest.createInstance(serverPath, 1.0, x0, y0, samplingFeatureSize, samplingFeatureSize);

				try {
					BufferedImage imgContent = server.readRegion(objRegion);
					BufferedImage imgBuf = new BufferedImage(imgContent.getWidth(), imgContent.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
					imgBuf.getGraphics().drawImage(imgContent, 0, 0, null);

					BufferedImage outputImgBuf;
					if (params.getBooleanParameterValue("normalization")) {
						double[][][] imgBufRGBAry = MacenkoStainingNormalizer.convertImageToRGBArray(imgBuf);
						double[][][] outputImgBufAry = MacenkoStainingNormalizer.deconvolutionBasedNormalization(
								imgBufRGBAry, est_W, MacenkoStainingNormalizer.targetStainReferenceMatrix, null, null, null, null);
						outputImgBuf = MacenkoStainingNormalizer.convertRGBArrayToImage(outputImgBufAry, BufferedImage.TYPE_3BYTE_BGR);
					} else {
						outputImgBuf = imgBuf;
					}

					BufferedImage imgSampled = params.getBooleanParameterValue("dontResampling") ? outputImgBuf
							: Scalr.resize(outputImgBuf, params.getIntParameterValue("samplingSize"));

					File imageFile = locationPath.resolve(objObject.getID().toString() + "." + fileExt).toFile();
					ImageIO.write(imgSampled, fileExt, imageFile);
				} catch (Exception e) {
					logger.error("Unable to export an image patch", e);
					success.set(false);
				}
			});

			if (!success.get()) {
				logger.error("Extracting the dataset failed!");
			}

			return hierarchy.getAnnotationObjects();
		}

		@Override
		public String getLastResultsDescription() {
			return lastResults;
		}
	}

	@Override
	protected void preprocess(TaskRunner taskRunner, ImageData<BufferedImage> imageData) {
		String dist = params.getStringParameterValue("dist");

		if (dist.isBlank()) {
			File dir = FileChoosers.promptForDirectory("Output directory", new File(objClsImgAcqDistProp.get()));
			if (dir != null) {
				objClsImgAcqDistProp.set(dir.toString());
			} else {
				lastResults = "No output directory is selected!";
				logger.warn(lastResults);
			}
		} else {
			objClsImgAcqDistProp.set(dist);
		}
	}

	@Override
	protected void postprocess(TaskRunner taskRunner, ImageData<BufferedImage> imageData) {
	}

	@Override
	public ParameterList getDefaultParameterList(ImageData<BufferedImage> imageData) {
		return params;
	}

	@Override
	public String getName() {
		return "Export images for object classification";
	}

	@Override
	public String getLastResultsDescription() {
		return lastResults;
	}

	@Override
	public String getDescription() {
		return "Export an image patch for each detection object inside the selected annotations";
	}

	@Override
	protected void addRunnableTasks(ImageData<BufferedImage> imageData, PathObject parentObject, List<Runnable> tasks) {
		tasks.add(DetectionPluginTools.createRunnableTask(new DetectedObjectImageSampling(), getParameterList(imageData), imageData, parentObject));
	}

	@Override
	protected Collection<? extends PathObject> getParentObjects(ImageData<BufferedImage> imageData) {
		PathObjectHierarchy hierarchy = imageData.getHierarchy();
		if (hierarchy.getTMAGrid() == null)
			return Collections.singleton(hierarchy.getRootObject());

		return hierarchy.getSelectionModel().getSelectedObjects().stream().filter(p -> p.isTMACore())
				.collect(Collectors.toList());
	}

	@Override
	public Collection<Class<? extends PathObject>> getSupportedParentObjectClasses() {
		List<Class<? extends PathObject>> list = new ArrayList<>();
		list.add(PathAnnotationObject.class);
		list.add(TMACoreObject.class);
		list.add(PathRootObject.class);
		return list;
	}
}
