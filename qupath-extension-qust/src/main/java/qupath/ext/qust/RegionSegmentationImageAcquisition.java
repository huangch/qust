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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import javax.imageio.ImageIO;

import org.imgscalr.Scalr;
import org.locationtech.jts.geom.Geometry;
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
import qupath.lib.plugins.AbstractInteractivePlugin;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * Exports a regular grid of image tiles covering the selected annotations. The tiles, together with the annotation
 * measurement table, form the training set of the region classifier.
 *
 * @author Chao Hui Huang
 */
public class RegionSegmentationImageAcquisition extends AbstractInteractivePlugin<BufferedImage> {

	private static final Logger logger = LoggerFactory.getLogger(RegionSegmentationImageAcquisition.class);
	private static final QuSTSetup qustSetup = QuSTSetup.getInstance();

	private static final StringProperty regSegImgAcqDistProp = PathPrefs.createPersistentPreference("regSegImgAcqDist", "");
	private static final DoubleProperty regSegImgAcqMppProp = PathPrefs.createPersistentPreference("regSegImgAcqMpp", 0.21233);
	private static final IntegerProperty regSegImgAcqSamplingSizeProp = PathPrefs.createPersistentPreference("regSegImgAcqSamplingSize", 224);
	private static final IntegerProperty regSegImgAcqSamplingStrideProp = PathPrefs.createPersistentPreference("regSegImgAcqSamplingStride", 112);
	private static final BooleanProperty regSegImgAcqAllSamplesProp = PathPrefs.createPersistentPreference("regSegImgAcqAllSamplesProp", true);
	private static final IntegerProperty regSegImgAcqSamplingNumProp = PathPrefs.createPersistentPreference("regSegImgAcqSamplingNum", 0);

	private static ParameterList params;
	private static String lastResults = null;

	public RegionSegmentationImageAcquisition() {
		params = new ParameterList()
				.addStringParameter("dist", "Destination folder", regSegImgAcqDistProp.get(), "Folder receiving the image tiles")
				.addEmptyParameter("").addEmptyParameter("Resampling using...")
				.addBooleanParameter("normalization", "Normalization (default: yes)", true, "Normalize the H&E staining of the tiles")
				.addBooleanParameter("dontResampling", "Do not rescale image (default: yes)", true, "Export the tiles at the pixel size of the image")
				.addEmptyParameter("...or...")
				.addDoubleParameter("mpp", "pixel size", regSegImgAcqMppProp.get(), GeneralTools.micrometerSymbol(), "Pixel size")
				.addEmptyParameter("")
				.addIntParameter("samplingSize", "Sampling size", regSegImgAcqSamplingSizeProp.get(), "pixel(s)", "Sampling size")
				.addIntParameter("samplingStride", "Sampling stride", regSegImgAcqSamplingStrideProp.get(), "pixel(s)", "Sampling stride")
				.addBooleanParameter("allSamples", "Include all samples (default: yes)", regSegImgAcqAllSamplesProp.get(), "Include all samples?")
				.addEmptyParameter("...or...")
				.addIntParameter("samplingNum", "Maximal sampling number", regSegImgAcqSamplingNumProp.get(), "tile(s)", "Maximal sampling number");
	}

	@Override
	public boolean runPlugin(TaskRunner taskRunner, ImageData<BufferedImage> imageData, String arg) {
		boolean success = super.runPlugin(taskRunner, imageData, arg);
		imageData.getHierarchy().fireHierarchyChangedEvent(this);
		return success;
	}

	@Override
	protected void addRunnableTasks(ImageData<BufferedImage> imageData, PathObject parentObject, List<Runnable> tasks) {
		tasks.add(new RegionSegmentationRunnable(imageData, parentObject, getParameterList(imageData)));
	}

	static class RegionSegmentationRunnable implements Runnable {
		private final ImageData<BufferedImage> imageData;
		private final ParameterList params;
		private final PathObject parentObject;

		public RegionSegmentationRunnable(ImageData<BufferedImage> imageData, PathObject parentObject, ParameterList params) {
			this.imageData = imageData;
			this.parentObject = parentObject;
			this.params = params;
		}

		@Override
		public void run() {
			if (!(parentObject instanceof PathAnnotationObject)) {
				return;
			}

			regSegImgAcqMppProp.set(params.getDoubleParameterValue("mpp"));
			regSegImgAcqSamplingSizeProp.set(params.getIntParameterValue("samplingSize"));
			regSegImgAcqSamplingStrideProp.set(params.getIntParameterValue("samplingStride"));
			regSegImgAcqAllSamplesProp.set(params.getBooleanParameterValue("allSamples"));
			regSegImgAcqSamplingNumProp.set(params.getIntParameterValue("samplingNum"));

			ImageServer<BufferedImage> server = imageData.getServer();
			String serverPath = server.getPath();

			double imageMpp = server.getPixelCalibration().getAveragedPixelSizeMicrons();
			double scalingFactor = params.getBooleanParameterValue("dontResampling") ? 1.0 : regSegImgAcqMppProp.get() / imageMpp;
			int featureSize = (int) (0.5 + scalingFactor * regSegImgAcqSamplingSizeProp.get());
			int stride = (int) (0.5 + scalingFactor * regSegImgAcqSamplingStrideProp.get());

			int segmentationWidth = 1 + (int) ((double) (server.getWidth() - featureSize) / (double) stride);
			int segmentationHeight = 1 + (int) ((double) (server.getHeight() - featureSize) / (double) stride);

			List<RegionRequest> allRegionList = new ArrayList<>();
			for (int y = 0; y < segmentationHeight; y++)
				for (int x = 0; x < segmentationWidth; x++)
					allRegionList.add(RegionRequest.createInstance(serverPath, 1.0, stride * x, stride * y, featureSize, featureSize));

			Geometry annotGeom = parentObject.getROI().getGeometry();
			List<RegionRequest> availRegionList = Collections.synchronizedList(new ArrayList<RegionRequest>());
			allRegionList.parallelStream().forEach(r -> {
				Geometry intersect = annotGeom.intersection(ROIs.createRectangleROI(r).getGeometry());
				if (!intersect.isEmpty())
					availRegionList.add(r);
			});

			Collections.shuffle(availRegionList);

			int normalizationSampleSize = qustSetup.getNormalizationSampleSize();
			if (availRegionList.size() < normalizationSampleSize) {
				logger.error("The number of tiles is smaller than the number required for normalization");
				return;
			}

			AtomicBoolean success = new AtomicBoolean(true);
			List<RegionRequest> normalizationSamplingRegionList = availRegionList.subList(0, normalizationSampleSize);
			List<BufferedImage> normalizationSamplingImageList = Collections.synchronizedList(new ArrayList<>());

			normalizationSamplingRegionList.parallelStream().forEach(r -> {
				if (!success.get()) return;

				try {
					BufferedImage imgContent = server.readRegion(r);
					BufferedImage imgBuf = new BufferedImage(imgContent.getWidth(), imgContent.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
					imgBuf.getGraphics().drawImage(imgContent, 0, 0, null);
					normalizationSamplingImageList.add(imgBuf);
				} catch (Exception e) {
					logger.error("Unable to read an image tile", e);
					success.set(false);
				}
			});

			if (!success.get()) {
				logger.error("Collecting the data for computing the normalization failed!");
				return;
			}

			BufferedImage normalizationSamplingImage = MacenkoStainingNormalizer.concatBufferedImages(normalizationSamplingImageList);
			double[][][] normalizationSamplingImageRGBAry = MacenkoStainingNormalizer.convertImageToRGBArray(normalizationSamplingImage);
			double[][][] normalizationSamplingImageSDAAry = MacenkoStainingNormalizer.rgbToSda(normalizationSamplingImageRGBAry, 255, false);
			double[][] est_W = MacenkoStainingNormalizer.separateStainsMacenkoPca(normalizationSamplingImageSDAAry);

			int samplingNum = params.getIntParameterValue("samplingNum") == 0
					|| params.getIntParameterValue("samplingNum") > availRegionList.size()
					|| params.getBooleanParameterValue("allSamples")
							? availRegionList.size()
							: params.getIntParameterValue("samplingNum");

			List<RegionRequest> samplingRegionList = availRegionList.subList(0, samplingNum);

			String imgFmt = qustSetup.getImageFileFormat().strip();
			String fileExt = imgFmt.charAt(0) == '.' ? imgFmt.substring(1) : imgFmt;

			String saveLocation = Paths.get(regSegImgAcqDistProp.get()).toString();
			new File(saveLocation).mkdirs();

			IntStream.range(0, samplingRegionList.size()).parallel().forEach(i -> {
				if (!success.get()) return;

				RegionRequest r = samplingRegionList.get(i);

				try {
					BufferedImage imgContent = server.readRegion(r);
					BufferedImage imgBuffer = new BufferedImage(imgContent.getWidth(), imgContent.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
					imgBuffer.getGraphics().drawImage(imgContent, 0, 0, null);

					BufferedImage outputImgBuf;
					if (params.getBooleanParameterValue("normalization")) {
						double[][][] imgBufferAry = MacenkoStainingNormalizer.convertImageToRGBArray(imgBuffer);
						double[][][] normaImgAry = MacenkoStainingNormalizer.deconvolutionBasedNormalization(
								imgBufferAry, est_W, MacenkoStainingNormalizer.targetStainReferenceMatrix, null,
								new String[] { "hematoxylin", "eosin", null }, null, null);
						outputImgBuf = MacenkoStainingNormalizer.convertRGBArrayToImage(normaImgAry, BufferedImage.TYPE_3BYTE_BGR);
					} else {
						outputImgBuf = imgBuffer;
					}

					BufferedImage imgSampled = params.getBooleanParameterValue("dontResampling") ? outputImgBuf
							: Scalr.resize(outputImgBuf, params.getIntParameterValue("samplingSize"));

					String filename = parentObject.getID().toString() + "." + r.getX() + "-" + r.getY();
					ImageIO.write(imgSampled, fileExt, Paths.get(saveLocation, filename + "." + fileExt).toFile());
				} catch (Exception e) {
					logger.error("Unable to export an image tile", e);
					success.set(false);
				}
			});

			if (!success.get())
				logger.error("Extracting the dataset failed!");
		}

		@Override
		public String toString() {
			return "Region segmentation image acquisition";
		}
	}

	@Override
	protected void preprocess(TaskRunner taskRunner, ImageData<BufferedImage> imageData) {
		String dist = params.getStringParameterValue("dist");

		if (dist.isBlank()) {
			File dir = FileChoosers.promptForDirectory("Output directory", new File(regSegImgAcqDistProp.get()));
			if (dir != null) {
				regSegImgAcqDistProp.set(dir.toString());
			} else {
				lastResults = "No output directory is selected!";
				logger.warn(lastResults);
			}
		} else {
			regSegImgAcqDistProp.set(dist);
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
		return "Export images for region segmentation";
	}

	@Override
	public String getLastResultsDescription() {
		return lastResults;
	}

	@Override
	public String getDescription() {
		return "Export a grid of image tiles covering the selected annotations";
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
		List<Class<? extends PathObject>> list = new ArrayList<>();
		list.add(PathAnnotationObject.class);
		list.add(TMACoreObject.class);
		list.add(PathRootObject.class);
		return list;
	}
}
