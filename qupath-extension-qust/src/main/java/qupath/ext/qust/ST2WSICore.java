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
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Vector;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bunwarpj.BSplineModel;
import bunwarpj.Transformation;
import bunwarpj.bUnwarpJ_;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.plugin.ContrastEnhancer;
import ij.plugin.ZProjector;
import ij.plugin.filter.BackgroundSubtracter;
import ij.plugin.filter.GaussianBlur;
import ij.process.ImageConverter;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import mpicbg.ij.InverseTransformMapping;
import mpicbg.ij.SIFT;
import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.models.AffineModel2D;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelType;
import qupath.lib.regions.RegionRequest;

/**
 * The ST2WSI registration of the DAPI image of a spatial transcriptomics slide to the H&amp;E whole slide image: the algorithm of the
 * ST2WSI Registration plugin of Fiji (<code>registration/</code> of the QuST repository), step by step and with the same parameters,
 * run on two QuPath image servers instead of two ImageJ images:
 * <ol>
 * <li>each image is read at a resolution level (the <em>series</em> of the Fiji plugin);</li>
 * <li>the DAPI image is rotated and flipped;</li>
 * <li>both images are prepared in the same way (multi-channel 8-bit composites become RGB, an RGB image is colour deconvolved and
 * inverted, the planes of a stack are averaged, then contrast equalization, 8-bit conversion, background subtraction, Gaussian blur and
 * equalization);</li>
 * <li>SIFT features are matched (nearest to second nearest ratio) and an affine model is fitted with RANSAC;</li>
 * <li>the DAPI image is aligned with the affine model and registered elastically (bUnwarpJ, B-splines);</li>
 * <li><code>registration_params.json</code> and <code>direct_transf.txt</code> (what the Xenium loader reads) and the quality-control
 * images are written.</li>
 * </ol>
 */
final class ST2WSICore {

	private static final Logger logger = LoggerFactory.getLogger(ST2WSICore.class);

	// the ImageJ commands (IJ.run) share static state
	private static final Object IJ_LOCK = new Object();

	private ST2WSICore() {}

	/** The parameters of a registration (the names and the defaults are those of the Fiji plugin). */
	static final class Settings {
		/** The series (resolution level, 1 is the full resolution) of the DAPI (reference) image and of the H&amp;E (target) image. */
		int refSeries = 3, tgtSeries = 3;
		boolean refFlipped = false;
		/** Rotation of the DAPI image in degrees: -270, -180, -90, 0, 90, 180 or 270. */
		String refRotated = "90";
		/** Pixel size of the full resolution DAPI image, in micrometres. */
		float pxlSz = 0.2125f;
		/** Colour deconvolution vectors ("H&amp;E" or "H&amp;E 2") and the stain used for the registration. */
		String tgtStain = "H&E";
		String tgtChannel = "Hematoxylon";
		int rolling = 50;
		float sigma = 12.0f;
		// SIFT
		float initialSigma = 1.6f;
		int steps = 3, minOctaveSize = 64, maxOctaveSize = 1024, fdSize = 4, fdBins = 8;
		float rod = 0.92f, maxEpsilon = 25.0f, minInlierRatio = 0.05f;
		int minNumInliers = 7;
		// bUnwarpJ
		int bUnwarpJMode = 0, imgSubsampFact = 0, minScaleDeformation = 0, maxScaleDeformation = 3;
		double divWeight = 0.0, curlWeight = 0.0, landmarkWeight = 0.0, imageWeight = 1.0, consistencyWeight = 10.0, stopThreshold = 0.01;
		/** Writes the prepared images too. */
		boolean verbose = false;
	}

	/** The image of a series: a resolution level of a QuPath image server. */
	private static final class Series {
		final ImageServer<BufferedImage> server;
		/** 0-based index of the level */
		final int index;
		final double downsample;
		/** The scaling factor to the full resolution image, as the Fiji plugin computes it (rounded to an integer). */
		final int scale;

		Series(ImageServer<BufferedImage> server, int series, String label) {
			this.server = server;
			int n = server.nResolutions();
			if (series < 1 || series > n)
				throw new IllegalArgumentException("The " + label + " image has no series " + series + ": it has " + n
						+ " resolution levels (series 1 to " + n + "). " + describe(server));
			this.index = series - 1;
			this.downsample = server.getDownsampleForResolution(index);
			this.scale = scalingFactor(server, index);
		}

		/** The whole image at this level: one plane per channel, or one RGB plane. */
		ImagePlus read(String title) throws IOException {
			RegionRequest request = RegionRequest.createInstance(server.getPath(), downsample, 0, 0, server.getWidth(), server.getHeight());
			BufferedImage img = server.readRegion(request);
			// the image of the level has the size the level reports (a request can round one more row or column)
			int w = Math.min(img.getWidth(), server.getMetadata().getLevel(index).getWidth());
			int h = Math.min(img.getHeight(), server.getMetadata().getLevel(index).getHeight());
			ImageStack stack = new ImageStack(w, h);
			if (isRgb(server)) {
				stack.addSlice(new ColorProcessor(w, h, img.getRGB(0, 0, w, h, null, 0, w)));
			} else {
				Raster raster = img.getRaster();
				for (int b = 0; b < raster.getNumBands(); b++)
					stack.addSlice(plane(raster, b, w, h));
			}
			return new ImagePlus(title, stack);
		}

		private static ImageProcessor plane(Raster raster, int band, int w, int h) {
			switch (raster.getDataBuffer().getDataType()) {
			case DataBuffer.TYPE_BYTE: {
				int[] samples = raster.getSamples(0, 0, w, h, band, (int[]) null);
				byte[] pixels = new byte[samples.length];
				for (int i = 0; i < pixels.length; i++)
					pixels[i] = (byte) samples[i];
				return new ByteProcessor(w, h, pixels, null);
			}
			case DataBuffer.TYPE_USHORT:
			case DataBuffer.TYPE_SHORT: {
				int[] samples = raster.getSamples(0, 0, w, h, band, (int[]) null);
				short[] pixels = new short[samples.length];
				for (int i = 0; i < pixels.length; i++)
					pixels[i] = (short) samples[i];
				return new ShortProcessor(w, h, pixels, null);
			}
			default:
				return new FloatProcessor(w, h, raster.getSamples(0, 0, w, h, band, (float[]) null));
			}
		}
	}

	/** An RGB image (a server of three 8-bit channels counts, whatever its channel names). */
	static boolean isRgb(ImageServer<BufferedImage> server) {
		return server.isRGB();
	}

	/** An 8-bit image of 3 or 4 channels that is not RGB: the Fiji plugin treats it as a composite and converts it to RGB. */
	private static boolean isComposite(ImageServer<BufferedImage> server) {
		return !server.isRGB() && (server.nChannels() == 3 || server.nChannels() == 4) && server.getPixelType() == PixelType.UINT8;
	}

	/** The scaling factor of the Fiji plugin (getScalingFactor): the mean of the width and the height ratios to series 1, rounded. */
	private static int scalingFactor(ImageServer<BufferedImage> server, int index) {
		float w0 = server.getMetadata().getLevel(0).getWidth(), h0 = server.getMetadata().getLevel(0).getHeight();
		float wN = server.getMetadata().getLevel(index).getWidth(), hN = server.getMetadata().getLevel(index).getHeight();
		return (int) (0.5 + ((w0 / wN) + (h0 / hN)) / 2.0);
	}

	/** The series of an image as a text: number, size, downsample and stored scaling factor (as the Fiji plugin lists them). */
	static String describe(ImageServer<BufferedImage> server) {
		StringBuilder sb = new StringBuilder("series, width x height, downsample, scale:");
		double w0 = server.getMetadata().getLevel(0).getWidth(), h0 = server.getMetadata().getLevel(0).getHeight();
		for (int i = 0; i < server.nResolutions(); i++) {
			double w = server.getMetadata().getLevel(i).getWidth(), h = server.getMetadata().getLevel(i).getHeight();
			double f = ((w0 / w) + (h0 / h)) / 2.0;
			int stored = (int) (0.5 + f);
			boolean exact = Math.abs(f - stored) <= 0.01 * Math.max(stored, 1);
			sb.append(String.format("%n  %d, %d x %d, %.4f, %d%s", i + 1, (int) w, (int) h, f, stored,
					exact ? "" : "   <-- not an integer: the scaling factors of the registration will be wrong"));
		}
		return sb.toString();
	}

	/** The labels of the series of an image for a pulldown list: the series number, the size and the downsample. */
	static List<String> seriesLabels(ImageServer<BufferedImage> server) {
		List<String> labels = new ArrayList<>();
		for (int i = 0; i < server.nResolutions(); i++)
			labels.add(String.format("%d (%d x %d, downsample %.4g)", i + 1, server.getMetadata().getLevel(i).getWidth(),
					server.getMetadata().getLevel(i).getHeight(), server.getDownsampleForResolution(i)));
		return labels;
	}

	/** The stain vectors of colourdeconvolution.txt of ImageJ. */
	private static double[][] stainVectors(String stain) {
		if ("H&E 2".equals(stain)) {
			return new double[][] { { 0.49015734, 0.76897085, 0.41040173 }, { 0.04615336, 0.84206840, 0.53739250 }, { 0, 0, 0 } };
		}
		return new double[][] { { 0.644211, 0.716556, 0.266844 }, { 0.092789, 0.954111, 0.283111 }, { 0, 0, 0 } };
	}

	private static void normalize(double[] v) {
		double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
		if (n > 0) {
			v[0] /= n;
			v[1] /= n;
			v[2] /= n;
		}
	}

	private static double[][] invert3x3(double[][] a) {
		double det = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0])
				+ a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0]);
		if (Math.abs(det) < 1e-12)
			throw new IllegalArgumentException("Deconvolution matrix is singular");
		double d = 1.0 / det;
		double[][] m = new double[3][3];
		m[0][0] = (a[1][1] * a[2][2] - a[1][2] * a[2][1]) * d;
		m[0][1] = -(a[0][1] * a[2][2] - a[0][2] * a[2][1]) * d;
		m[0][2] = (a[0][1] * a[1][2] - a[0][2] * a[1][1]) * d;
		m[1][0] = -(a[1][0] * a[2][2] - a[1][2] * a[2][0]) * d;
		m[1][1] = (a[0][0] * a[2][2] - a[0][2] * a[2][0]) * d;
		m[1][2] = -(a[0][0] * a[1][2] - a[0][2] * a[1][0]) * d;
		m[2][0] = (a[1][0] * a[2][1] - a[1][1] * a[2][0]) * d;
		m[2][1] = -(a[0][0] * a[2][1] - a[0][1] * a[2][0]) * d;
		m[2][2] = (a[0][0] * a[1][1] - a[0][1] * a[1][0]) * d;
		return m;
	}

	/** Colour deconvolution of an RGB image (Ruifrok and Johnston), the selected stain as 8-bit intensity (the headless one of the Fiji plugin). */
	private static ImagePlus deconvolve(ImagePlus rgb, String channel, String stain) {
		int w = rgb.getWidth(), h = rgb.getHeight();
		ImageProcessor proc = rgb.getProcessor();
		int channelIdx = "Eosin".equals(channel) ? 1 : "Residual".equals(channel) ? 2 : 0;
		double[][] v = stainVectors(stain);
		double[] hVec = v[0].clone(), eVec = v[1].clone(), dVec = v[2].clone();
		normalize(hVec);
		normalize(eVec);
		if (dVec[0] == 0 && dVec[1] == 0 && dVec[2] == 0) {
			dVec = new double[] { hVec[1] * eVec[2] - hVec[2] * eVec[1], hVec[2] * eVec[0] - hVec[0] * eVec[2],
					hVec[0] * eVec[1] - hVec[1] * eVec[0] };
		}
		normalize(dVec);
		double[][] inv = invert3x3(new double[][] { { hVec[0], eVec[0], dVec[0] }, { hVec[1], eVec[1], dVec[1] }, { hVec[2], eVec[2], dVec[2] } });

		byte[] out = new byte[w * h];
		int[] rgbValues = new int[3];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				proc.getPixel(x, y, rgbValues);
				double odR = -Math.log((rgbValues[0] + 1.0) / 255.0);
				double odG = -Math.log((rgbValues[1] + 1.0) / 255.0);
				double odB = -Math.log((rgbValues[2] + 1.0) / 255.0);
				double c = inv[channelIdx][0] * odR + inv[channelIdx][1] * odG + inv[channelIdx][2] * odB;
				if (c < 0)
					c = 0;
				int intensity = (int) Math.round(255.0 * Math.exp(-c));
				out[y * w + x] = (byte) Math.max(0, Math.min(255, intensity));
			}
		}
		return new ImagePlus(rgb.getTitle() + "-(Colour_" + (channelIdx + 1) + ")", new ByteProcessor(w, h, out, null));
	}

	/** Rotates every plane of the stack by a multiple of 90 degrees (positive: to the right), as "Rotate 90 Degrees Right/Left" does. */
	private static ImagePlus rotate(ImagePlus imp, int degrees) {
		int quarterTurns = ((degrees / 90) % 4 + 4) % 4;
		if (quarterTurns == 0)
			return imp;
		ImageStack stack = null;
		for (int i = 1; i <= imp.getStackSize(); i++) {
			ImageProcessor ip = imp.getStack().getProcessor(i);
			for (int t = 0; t < quarterTurns; t++)
				ip = ip.rotateRight();
			if (stack == null)
				stack = new ImageStack(ip.getWidth(), ip.getHeight());
			stack.addSlice(ip);
		}
		return new ImagePlus(imp.getTitle(), stack);
	}

	private static ImagePlus flipHorizontally(ImagePlus imp) {
		ImageStack stack = new ImageStack(imp.getWidth(), imp.getHeight());
		for (int i = 1; i <= imp.getStackSize(); i++) {
			ImageProcessor ip = imp.getStack().getProcessor(i).duplicate();
			ip.flipHorizontal();
			stack.addSlice(ip);
		}
		return new ImagePlus(imp.getTitle(), stack);
	}

	/**
	 * The preparation of an image before the feature extraction, in the order of the Fiji plugin: a multi-channel 8-bit composite
	 * becomes RGB, an RGB image is colour deconvolved and inverted, the planes of a stack are averaged, then the normalization
	 * (contrast equalization, 8-bit) and the denoising (background subtraction, Gaussian blur, equalization).
	 */
	private static ImagePlus prepare(ImagePlus imp, boolean composite, Settings s, String label) {
		if (composite && imp.getStackSize() >= 3) {
			logger.info("Convert {} multi-channel composite to RGB color", label);
			int w = imp.getWidth(), h = imp.getHeight();
			ImageStack st = imp.getStack();
			int[] rgb = new int[w * h];
			for (int i = 0; i < rgb.length; i++)
				rgb[i] = ((st.getProcessor(1).get(i) & 0xff) << 16) | ((st.getProcessor(2).get(i) & 0xff) << 8) | (st.getProcessor(3).get(i) & 0xff);
			imp = new ImagePlus(label, new ColorProcessor(w, h, rgb));
		}
		synchronized (IJ_LOCK) {
			if (imp.getType() == ImagePlus.COLOR_RGB && imp.getStackSize() == 1) {
				logger.info("Colour deconvolution for {} (vectors={}, channel={})", label, s.tgtStain, s.tgtChannel);
				imp = deconvolve(imp, s.tgtChannel, s.tgtStain);
				imp.getProcessor().invert();
			}
			if (imp.getStackSize() > 1) {
				logger.info("Z projection for {}", label);
				imp = ZProjector.run(imp, "avg");
			}
			if (imp.getStackSize() == 1 && imp.getType() != ImagePlus.COLOR_RGB) {
				logger.info("Normalize {}", label);
				// the ImageJ commands "Enhance Contrast... saturated=0 equalize" and "8-bit", called directly (IJ.run needs a display)
				new ContrastEnhancer().equalize(imp);
				new ImageConverter(imp).convertToGray8();
			}
			if (imp.getType() == ImagePlus.GRAY8) {
				logger.info("Denoising {}", label);
				// "Subtract Background... rolling=r", "Gaussian Blur... sigma=s" and "Enhance Contrast... saturated=0 equalize"
				ImageProcessor ip = imp.getProcessor();
				new BackgroundSubtracter().rollingBallBackground(ip, s.rolling, false, false, false, true, true);
				new GaussianBlur().blurGaussian(ip, s.sigma, s.sigma, 0.002);
				new ContrastEnhancer().equalize(imp);
			}
		}
		return imp;
	}

	/**
	 * The two-plane stack and a magenta/green composite of the alignment: the target in green, the aligned source in magenta; where
	 * they agree the overlap reads grey or white.
	 */
	private static void saveAlignmentCheck(ImageProcessor target, ImageProcessor source, Path outputDir, String stem) {
		try {
			ImageStack stack = new ImageStack(target.getWidth(), target.getHeight());
			stack.addSlice("target", target);
			stack.addSlice("aligned", source);
			new FileSaver(new ImagePlus(stem, stack)).saveAsTiffStack(outputDir.resolve(stem + "_stack.tif").toString());

			ImageProcessor t8 = target.convertToByte(true), s8 = source.convertToByte(true);
			int w = Math.min(t8.getWidth(), s8.getWidth()), h = Math.min(t8.getHeight(), s8.getHeight());
			ColorProcessor cp = new ColorProcessor(w, h);
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int g = t8.get(x, y) & 0xff, m = s8.get(x, y) & 0xff;
					cp.set(x, y, (m << 16) | (g << 8) | m);
				}
			}
			new FileSaver(new ImagePlus(stem, cp)).saveAsPng(outputDir.resolve(stem + "_overlay.png").toString());
			logger.info("QC written: {}_stack.tif, {}_overlay.png", stem, stem);
		} catch (RuntimeException e) {
			logger.warn("Could not write the {} QC images: {}", stem, e.getMessage());
		}
	}

	private static String plain(double v) {
		return BigDecimal.valueOf(v).toPlainString();
	}

	/**
	 * Registers the DAPI image (reference) to the H&amp;E image (target) and writes registration_params.json, direct_transf.txt and the
	 * quality-control images into the output folder.
	 * @return a description of the result
	 */
	static String register(ImageServer<BufferedImage> dapi, ImageServer<BufferedImage> he, Settings s, Path outputDir) throws Exception {
		Files.createDirectories(outputDir);
		logger.info("ST2WSI starts...");
		logger.info("DAPI image, {}", describe(dapi));
		logger.info("H&E image, {}", describe(he));
		Series ref = new Series(dapi, s.refSeries, "DAPI");
		Series tgt = new Series(he, s.tgtSeries, "H&E");
		logger.info("Series used: DAPI {} (scale {}), H&E {} (scale {})", s.refSeries, ref.scale, s.tgtSeries, tgt.scale);

		ImagePlus refImg = ref.read("DAPI");
		ImagePlus tgtImg = tgt.read("H&E");
		int refImgWidth = refImg.getWidth(), refImgHeight = refImg.getHeight();
		boolean refRgb = refImg.getType() == ImagePlus.COLOR_RGB, tgtRgb = tgtImg.getType() == ImagePlus.COLOR_RGB;
		if (refRgb && !tgtRgb) {
			logger.warn("The reference image is RGB and the target is not: the DAPI image file should be the DAPI/ST image and the open image the H&E slide");
		}

		// the DAPI image is rotated and flipped
		refImg = rotate(refImg, Integer.parseInt(s.refRotated));
		if (s.refFlipped)
			refImg = flipHorizontally(refImg);

		refImg = prepare(refImg, isComposite(dapi), s, "DAPI");
		tgtImg = prepare(tgtImg, isComposite(he), s, "H&E");
		if (s.verbose) {
			new FileSaver(refImg).saveAsPng(outputDir.resolve("prepared_dapi.png").toString());
			new FileSaver(tgtImg).saveAsPng(outputDir.resolve("prepared_he.png").toString());
		}

		// SIFT feature extraction and matching
		logger.info("Extracting SIFT features...");
		FloatArray2DSIFT.Param siftParam = new FloatArray2DSIFT.Param();
		siftParam.initialSigma = s.initialSigma;
		siftParam.steps = s.steps;
		siftParam.minOctaveSize = s.minOctaveSize;
		siftParam.maxOctaveSize = s.maxOctaveSize;
		siftParam.fdSize = s.fdSize;
		siftParam.fdBins = s.fdBins;
		SIFT ijSIFT = new SIFT(new FloatArray2DSIFT(siftParam));
		Collection<Feature> fs1 = new ArrayList<>(), fs2 = new ArrayList<>();
		ijSIFT.extractFeatures(refImg.getProcessor(), fs1);
		ijSIFT.extractFeatures(tgtImg.getProcessor(), fs2);
		logger.info("Reference image features: {}", fs1.size());
		logger.info("Target  image features: {}", fs2.size());
		if (fs1.isEmpty() || fs2.isEmpty())
			throw new IllegalStateException("No SIFT features found in one or both images");

		logger.info("Matching features...");
		Vector<PointMatch> candidates = new Vector<>();
		for (Feature f1 : fs1) {
			Feature best = null, secondBest = null;
			double bestDistance = Float.MAX_VALUE, secondBestDistance = Float.MAX_VALUE;
			for (Feature f2 : fs2) {
				double distance = f1.descriptorDistance(f2);
				if (distance < bestDistance) {
					secondBest = best;
					secondBestDistance = bestDistance;
					best = f2;
					bestDistance = distance;
				} else if (distance < secondBestDistance) {
					secondBest = f2;
					secondBestDistance = distance;
				}
			}
			if (best != null && secondBest != null && bestDistance / secondBestDistance < s.rod) {
				double[] p1 = { f1.location[0], f1.location[1] }, p2 = { best.location[0], best.location[1] };
				candidates.add(new PointMatch(new Point(p1, p1.clone()), new Point(p2, p2.clone())));
			}
		}
		logger.info("Feature matches found: {}", candidates.size());
		if (candidates.size() < 4)
			throw new IllegalStateException("Insufficient feature matches found: " + candidates.size()
					+ " (try the other colour deconvolution vectors, H&E 2, or check the rotation and the flip of the DAPI image)");

		// affine model with RANSAC
		AffineModel2D affineModel = new AffineModel2D();
		Vector<PointMatch> inliers = new Vector<>();
		if (!affineModel.filterRansac(candidates, inliers, 1000, s.maxEpsilon, s.minInlierRatio, s.minNumInliers))
			throw new IllegalStateException("Failed to find reliable transformation model (check the rotation and the flip of the DAPI image)");
		logger.info("Inliers found: {} / {}", inliers.size(), candidates.size());
		logger.info("Inlier ratio: {}", String.format("%.3f", (float) inliers.size() / candidates.size()));
		double[] affine = new double[6];
		affineModel.toArray(affine);
		logger.info("Transformation Matrix: AffineTransform[[{}, {}, {}], [{}, {}, {}]]", plain(affine[0]), plain(affine[2]), plain(affine[4]),
				plain(affine[1]), plain(affine[3]), plain(affine[5]));

		ImageProcessor refIp = refImg.getProcessor();
		ImageProcessor transformedIp = refIp.createProcessor(tgtImg.getWidth(), tgtImg.getHeight());
		new InverseTransformMapping<AffineModel2D>(affineModel).mapInterpolated(refIp, transformedIp);
		ImagePlus refTransformedImg = new ImagePlus(refImg.getTitle() + "_aligned", transformedIp);
		saveAlignmentCheck(tgtImg.getProcessor(), transformedIp, outputDir, "alignment_sift");

		// elastic registration
		logger.info("Performing bUnwarpJ registration...");
		Transformation warp = bUnwarpJ_.computeTransformationBatch(tgtImg, refTransformedImg, null, null, s.bUnwarpJMode, s.imgSubsampFact,
				s.minScaleDeformation, s.maxScaleDeformation, s.divWeight, s.curlWeight, s.landmarkWeight, s.imageWeight, s.consistencyWeight,
				s.stopThreshold);
		if (warp == null)
			throw new IllegalStateException("Registration failed - could not compute transformation.");
		double[][] cx = warp.getDirectDeformationCoefficientsX(), cy = warp.getDirectDeformationCoefficientsY();
		int intervals = warp.getIntervals();

		// The overlay of the elastic result is for checking only: a failure here must not lose the registration
		try {
			ImagePlus warped = new ImagePlus("warped", transformedIp.duplicate());
			// bUnwarpJ splits the rows over the processors and breaks when the height is odd and there are many processors
			// (a negative block height), so it is given a target of even height
			ImagePlus tgtForWarp = tgtImg.getHeight() % 2 == 0 ? tgtImg : new ImagePlus("target", new ByteProcessor(tgtImg.getWidth(), tgtImg.getHeight() + 1));
			ImageProcessor warpedIp = bunwarpj.MiscTools.applyTransformationMT(warped, tgtForWarp,
					new BSplineModel(warped.getProcessor(), false, s.imgSubsampFact), intervals, cx, cy);
			saveAlignmentCheck(tgtImg.getProcessor(), warpedIp, outputDir, "alignment_bunwarpj");
		} catch (RuntimeException e) {
			logger.warn("The overlay of the elastic registration could not be written: {}", e.toString());
		}

		// the parameters the Xenium loader reads
		JSONArray matrix = new JSONArray();
		for (int i : new int[] { 0, 2, 4, 1, 3, 5 })
			matrix.put(plain(affine[i]));
		JSONObject json = new JSONObject();
		json.put("xnumAnnotImgRegParamSrcImgWidth", refImgWidth * ref.scale);
		json.put("xnumAnnotImgRegParamSrcImgHeight", refImgHeight * ref.scale);
		json.put("xnumAnnotImgRegParamFlipHori", s.refFlipped);
		json.put("xnumAnnotImgRegParamFlipVert", false);
		json.put("xnumAnnotImgRegParamDapiImgPxlSize", s.pxlSz);
		json.put("xnumAnnotImgRegParamRotation", s.refRotated);
		json.put("xnumAnnotImgRegParamSiftMatrix", matrix);
		json.put("xnumAnnotImgRegParamSourceScale", ref.scale);
		json.put("xnumAnnotImgRegParamTargetScale", tgt.scale);
		Files.writeString(outputDir.resolve("registration_params.json"), json.toString(), StandardCharsets.UTF_8);
		bunwarpj.MiscTools.saveElasticTransformation(intervals, cx, cy, outputDir.resolve("direct_transf.txt").toString());

		return String.format("Registration written to %s: %d SIFT inliers of %d matches, affine [%s, %s, %s, %s, %s, %s]", outputDir, inliers.size(),
				candidates.size(), plain(affine[0]), plain(affine[2]), plain(affine[4]), plain(affine[1]), plain(affine[3]), plain(affine[5]));
	}
}
