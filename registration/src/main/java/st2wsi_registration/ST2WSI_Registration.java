/*-
 * SPDX-License-Identifier: GPL-3.0-or-later
 * #%L
 * ST2WSI_Registration plugin for Fiji.
 * %%
 * Copyright (C) 2024 QuST developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * http://www.gnu.org/licenses/gpl-3.0.html.
 * #L%
 */
package st2wsi_registration;

/**
 * ST2WSI_Registration: Spatial Transcriptomics to Whole Slide Image Registration
 * 
 * An ImageJ/Fiji plugin for registering DAPI images from spatial transcriptomics
 * platforms (10x Xenium, Visium, etc.) to H&E-stained whole slide images (WSI).
 * 
 * This implements the registration workflow described in:
 *   Huang, Lichtarge and Fernandez. "Integrative whole slide image and spatial
 *   transcriptomics analysis with QuST and QuPath." npj Precision Oncology 9, 70 (2025).
 *   https://doi.org/10.1038/s41698-025-00841-9
 * 
 * The pipeline consists of:
 *   1. Colour deconvolution to extract haematoxylin channel from H&E
 *   2. SIFT-based affine alignment for coarse registration
 *   3. B-spline elastic registration (bUnwarpJ) for fine deformable alignment
 * 
 * Usage (headless):
 *   /path/to/Fiji.app/ImageJ-linux64 \
 *       --headless --ij2 \
 *       --run "st2wsi_registration.ST2WSI_Registration" \
 *       "outputDir=/data/out \
 *        refImagePath=/data/dapi.ome.tif \
 *        tgtImagePath=/data/wsi.ome.tif \
 *        refSeries=3 \
 *        tgtSeries=3 \
 *        refFlipped=false \
 *        refRotated=90 \
 *        tgtChannel=Hematoxylon \
 *        tgtStain=[H&E 2]"
 *
 * tgtStain picks the colour-deconvolution vector set from
 * colourdeconvolution.txt: "H&E" (default) or "H&E 2". Slides whose
 * haematoxylin separates poorly under one set often register cleanly under
 * the other, so this is worth trying before declaring a slide unregistrable.
 *
 * This plugin builds upon bUnwarpJ by Ignacio Arganda-Carreras and Jan Kybic,
 * and the SIFT implementation from MPICBG by Stephan Saalfeld.
 */

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.NonBlockingGenericDialog;
import ij.io.DirectoryChooser;
import ij.io.FileSaver;
import ij.plugin.PlugIn;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import mpicbg.ij.SIFT;
import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.models.AffineModel2D;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;

import bunwarpj.BSplineModel;
import bunwarpj.bUnwarpJ_;

import java.awt.Button;
import java.awt.FlowLayout;
import java.awt.Panel;
import java.awt.TextField;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.GraphicsEnvironment;

import java.io.FileWriter;
import java.io.IOException;

import java.lang.reflect.Field;

import java.math.BigDecimal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import org.json.JSONArray;
import org.json.JSONObject;

import loci.formats.ImageReader;
import loci.formats.FormatException;
import loci.formats.FormatTools;

/*====================================================================
|   ST2WSI_Registration
\===================================================================*/

/**
 * Main class for the image registration plugin for ImageJ/Fiji.
 * It allows pairwise image registration combining SIFT-based affine alignment
 * and elastic registration based on bUnwarpJ B-spline models.
 */
public class ST2WSI_Registration implements PlugIn, ActionListener {

    /* ------------------------------------------------------------------
     * GUI fields
     * ------------------------------------------------------------------ */

    private TextField dirField;
    private Button browseButton;

    /* ------------------------------------------------------------------
     * Helper: CLI argument parsing
     * ------------------------------------------------------------------ */

    public static Map<String, String> parseArg(String arg) {
        Map<String, String> map = new HashMap<>();
        if (arg == null || arg.trim().isEmpty())
            return map;
        // Values may contain spaces when bracketed or quoted ("H&E 2"), so split
        // on separators only at bracket/quote depth zero.
        List<String> pairs = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        int depth = 0;
        for (int i = 0; i < arg.trim().length(); i++) {
            char c = arg.trim().charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                cur.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
                cur.append(c);
            } else if (c == '[') {
                depth++;
                cur.append(c);
            } else if (c == ']') {
                if (depth > 0) depth--;
                cur.append(c);
            } else if (depth == 0 && (c == ',' || Character.isWhitespace(c))) {
                if (cur.length() > 0) {
                    pairs.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) pairs.add(cur.toString());

        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx > 0 && idx < pair.length() - 1) {
                String key = pair.substring(0, idx).trim();
                String value = pair.substring(idx + 1).trim();
                if (value.length() >= 2) {
                    char first = value.charAt(0);
                    char last = value.charAt(value.length() - 1);
                    if ((first == '\'' && last == '\'')
                            || (first == '"' && last == '"')
                            || (first == '[' && last == ']')) {
                        value = value.substring(1, value.length() - 1);
                    }
                }
                map.put(key, value);
            }
        }
        return map;
    }

    /* ------------------------------------------------------------------
     * Helper: byte buffer conversions
     * ------------------------------------------------------------------ */

    private static short[] bytesToShorts(byte[] bytes, boolean littleEndian) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        bb.order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        ShortBuffer sb = bb.asShortBuffer();
        short[] result = new short[sb.remaining()];
        sb.get(result);
        return result;
    }

    private static float[] bytesToFloats(byte[] bytes, boolean littleEndian) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        bb.order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        FloatBuffer fb = bb.asFloatBuffer();
        float[] result = new float[fb.remaining()];
        fb.get(result);
        return result;
    }

    // Helper: Convert raw bytes to ImageProcessor based on pixel type
    private static ImageProcessor makeProcessor(
        byte[] bytes,
        int width,
        int height,
        int pixelType,
        boolean littleEndian,
        boolean isRGB
    ) {
        switch (pixelType) {
            case FormatTools.UINT8:
            case FormatTools.INT8:
                if (!isRGB) {
                    return new ByteProcessor(width, height, bytes, null);
                } else {
                    int nPixels = width * height;
                    int[] rgbInts = new int[nPixels];
                    for (int i = 0, j = 0; i < nPixels; i++) {
                        int r = bytes[j++] & 0xFF;
                        int g = bytes[j++] & 0xFF;
                        int b = bytes[j++] & 0xFF;
                        rgbInts[i] = (r << 16) | (g << 8) | b; // 0xRRGGBB
                    }
                    return new ColorProcessor(width, height, rgbInts);
                }
            case FormatTools.UINT16:
            case FormatTools.INT16:
                short[] shorts = bytesToShorts(bytes, littleEndian);
                return new ShortProcessor(width, height, shorts, null);
            case FormatTools.FLOAT:
                float[] floats = bytesToFloats(bytes, littleEndian);
                return new FloatProcessor(width, height, floats);
        }
        throw new IllegalArgumentException("Unsupported pixel type: " + FormatTools.getPixelTypeString(pixelType));
    }

    private static int getScalingFactor(String imagePath, int seriesIndex) throws Exception {
        try {
            ImageReader reader0 = new ImageReader();
            reader0.setId(imagePath);
            reader0.setSeries(0);
            float width0 = (float) reader0.getSizeX();
            float height0 = (float) reader0.getSizeY();
            reader0.close();

            ImageReader readerN = new ImageReader();
            readerN.setId(imagePath);
            readerN.setSeries(seriesIndex);
            float widthN = (float) readerN.getSizeX();
            float heightN = (float) readerN.getSizeY();
            readerN.close();

            return (int) (0.5 + ((width0 / widthN) + (height0 / heightN)) / 2.0);
        } catch (FormatException | IOException e) {
            e.printStackTrace();
            throw new Exception(e);
        }
    }

    private static ImagePlus readImage(String imagePath, int targetSeries) throws Exception {
        ImagePlus imp = null;
        try {
            ImageReader reader = new ImageReader();
            reader.setId(imagePath);
            reader.setSeries(targetSeries);
            int width = reader.getSizeX();
            int height = reader.getSizeY();
            int pixelType = reader.getPixelType();
            boolean littleEndian = reader.isLittleEndian();
            int imageCount = reader.getImageCount(); // Z * T * C
            ImageStack stack = new ImageStack(width, height);
            for (int i = 0; i < imageCount; i++) {
                byte[] bytes = reader.openBytes(i);
                ImageProcessor ip = makeProcessor(bytes, width, height, pixelType, littleEndian, reader.isRGB());
                stack.addSlice("Plane " + i, ip);
            }
            imp = new ImagePlus("Series " + targetSeries, stack);
            reader.close();
        } catch (FormatException | IOException e) {
            e.printStackTrace();
            throw new Exception(e);
        }
        return imp;
    }

    /**
     * Write the two-slice stack plus a magenta/green composite so headless runs
     * can be checked without a display. Target is green, aligned source magenta;
     * where they agree the overlap reads grey/white.
     */
    private static void saveAlignmentCheck(ImagePlus stackImp, ImageProcessor tgtIp,
                                           ImageProcessor srcIp, String outputDir,
                                           String stem) {
        try {
            new FileSaver(stackImp).saveAsTiffStack(
                Paths.get(outputDir, stem + "_stack.tif").toString());

            ImageProcessor t8 = tgtIp.convertToByte(true);
            ImageProcessor s8 = srcIp.convertToByte(true);
            int w = Math.min(t8.getWidth(), s8.getWidth());
            int h = Math.min(t8.getHeight(), s8.getHeight());
            ColorProcessor cp = new ColorProcessor(w, h);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int g = t8.get(x, y) & 0xff;
                    int m = s8.get(x, y) & 0xff;
                    cp.set(x, y, (m << 16) | (g << 8) | m);
                }
            }
            new FileSaver(new ImagePlus(stem, cp)).saveAsPng(
                Paths.get(outputDir, stem + "_overlay.png").toString());
            IJ.log("  QC written: " + stem + "_stack.tif, " + stem + "_overlay.png");
        } catch (Exception e) {
            IJ.log("  WARNING: could not write " + stem + " QC images: " + e.getMessage());
        }
    }

    /**
     * Print the pyramid the way getScalingFactor() sees it, using 1-based indices
     * so the numbers can be passed straight to refSeries / tgtSeries.
     */
    private static void listSeries(String label, String imagePath) {
        IJ.log(label + ": " + imagePath);
        try {
            ImageReader reader = new ImageReader();
            reader.setId(imagePath);
            int n = reader.getSeriesCount();
            reader.setSeries(0);
            double w0 = reader.getSizeX();
            double h0 = reader.getSizeY();
            IJ.log(String.format("  %-7s %-16s %-11s %-7s", "series", "width x height",
                                 "downsample", "stored"));
            for (int i = 0; i < n; i++) {
                reader.setSeries(i);
                double w = reader.getSizeX();
                double h = reader.getSizeY();
                double f = ((w0 / w) + (h0 / h)) / 2.0;
                int stored = (int) (0.5 + f);
                String warn = Math.abs(f - stored) > 0.01 * Math.max(stored, 1)
                    ? "   <-- non-integer: SourceScale/TargetScale will be wrong" : "";
                IJ.log(String.format("  %-7d %-16s %-11.4f %-7d%s", i + 1,
                                     ((int) w) + " x " + ((int) h), f, stored, warn));
            }
            reader.close();
        } catch (Exception e) {
            IJ.log("  ERROR: " + e.getMessage());
        }
    }

    private static double[][] invert3x3(double[][] a) {
        double det =
            a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
            a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) +
            a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0]);
        if (Math.abs(det) < 1e-12) {
            throw new IllegalArgumentException("Deconvolution matrix is singular");
        }
        double invDet = 1.0 / det;
        double[][] m = new double[3][3];
        m[0][0] =  (a[1][1] * a[2][2] - a[1][2] * a[2][1]) * invDet;
        m[0][1] = -(a[0][1] * a[2][2] - a[0][2] * a[2][1]) * invDet;
        m[0][2] =  (a[0][1] * a[1][2] - a[0][2] * a[1][1]) * invDet;
        m[1][0] = -(a[1][0] * a[2][2] - a[1][2] * a[2][0]) * invDet;
        m[1][1] =  (a[0][0] * a[2][2] - a[0][2] * a[2][0]) * invDet;
        m[1][2] = -(a[0][0] * a[1][2] - a[0][2] * a[1][0]) * invDet;
        m[2][0] =  (a[1][0] * a[2][1] - a[1][1] * a[2][0]) * invDet;
        m[2][1] = -(a[0][0] * a[2][1] - a[0][1] * a[2][0]) * invDet;
        m[2][2] =  (a[0][0] * a[1][1] - a[0][1] * a[1][0]) * invDet;
        return m;
    }

    private static void normalizeVec(double[] v) {
        double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (n > 0) {
            v[0] /= n;
            v[1] /= n;
            v[2] /= n;
        }
    }

    /** Stain vectors as published in colourdeconvolution.txt. */
    private static double[][] stainVectors(String stain) {
        if ("H&E 2".equals(stain)) {
            return new double[][] {
                {0.49015734, 0.76897085, 0.41040173},
                {0.04615336, 0.84206840, 0.53739250},
                {0.0, 0.0, 0.0}
            };
        }
        return new double[][] {
            {0.644211, 0.716556, 0.266844},
            {0.092789, 0.954111, 0.283111},
            {0.0, 0.0, 0.0}
        };
    }

    private static ImagePlus deconvolveHEDHeadless(ImagePlus rgbImg, String tgtChannel,
                                                   String stain) {
        int w = rgbImg.getWidth();
        int h = rgbImg.getHeight();
        ImageProcessor proc = rgbImg.getProcessor();
        if (!(proc instanceof ColorProcessor)) {
            throw new IllegalArgumentException("Expected RGB image for deconvolution");
        }

        int channelIdx = 0;
        if ("Eosin".equals(tgtChannel)) channelIdx = 1;
        else if ("Residual".equals(tgtChannel)) channelIdx = 2;

        double[][] v = stainVectors(stain);
        double[] hVec = v[0].clone();
        double[] eVec = v[1].clone();
        double[] dVec = v[2].clone();
        normalizeVec(hVec);
        normalizeVec(eVec);
        // colourdeconvolution.txt leaves the third vector zero for two-stain
        // recipes; Ruifrok's method fills it with the cross product so the
        // basis stays invertible.
        if (dVec[0] == 0 && dVec[1] == 0 && dVec[2] == 0) {
            dVec = new double[] {
                hVec[1] * eVec[2] - hVec[2] * eVec[1],
                hVec[2] * eVec[0] - hVec[0] * eVec[2],
                hVec[0] * eVec[1] - hVec[1] * eVec[0]
            };
        }
        normalizeVec(dVec);

        double[][] m = new double[][] {
            {hVec[0], eVec[0], dVec[0]},
            {hVec[1], eVec[1], dVec[1]},
            {hVec[2], eVec[2], dVec[2]}
        };
        double[][] inv = invert3x3(m);

        byte[] out = new byte[w * h];
        int[] rgb = new int[3];
        final double eps = 1.0;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                proc.getPixel(x, y, rgb);
                double r = rgb[0];
                double g = rgb[1];
                double b = rgb[2];

                double odR = -Math.log((r + eps) / 255.0);
                double odG = -Math.log((g + eps) / 255.0);
                double odB = -Math.log((b + eps) / 255.0);

                double c0 = inv[0][0] * odR + inv[0][1] * odG + inv[0][2] * odB;
                double c1 = inv[1][0] * odR + inv[1][1] * odG + inv[1][2] * odB;
                double c2 = inv[2][0] * odR + inv[2][1] * odG + inv[2][2] * odB;

                double c = channelIdx == 0 ? c0 : (channelIdx == 1 ? c1 : c2);
                if (c < 0) c = 0;

                int intensity = (int) Math.round(255.0 * Math.exp(-c));
                if (intensity < 0) intensity = 0;
                if (intensity > 255) intensity = 255;
                out[y * w + x] = (byte) (intensity & 0xFF);
            }
        }

        ImagePlus gray = new ImagePlus(rgbImg.getTitle() + "-(Colour_" + (channelIdx + 1) + ")", new ByteProcessor(w, h, out, null));
        return gray;
    }

    @SuppressWarnings("unchecked")
    private static Vector<ImagePlus> getImageList() throws Exception {
        Field field = ImagePlus.class.getDeclaredField("imageList");
        field.setAccessible(true);
        return new Vector<>((Vector<ImagePlus>) field.get(null));
    }

    /* ------------------------------------------------------------------
     * Plugin entry point
     * ------------------------------------------------------------------ */

    @Override
    public void run(String arg) {

        // ---- Parse CLI-style arguments ----
        Map<String, String> params = parseArg(arg);
        boolean missingCliKeys =
            !params.containsKey("outputDir") ||
            !params.containsKey("refImagePath") ||
            !params.containsKey("tgtImagePath");

        if (params.isEmpty() || missingCliKeys) {
            String envArg = System.getenv("ST2WSI_ARG");
            if (envArg != null && !envArg.trim().isEmpty()) {
                IJ.log("ST2WSI_Registration: using ST2WSI_ARG environment fallback.");
                params = parseArg(envArg);
            }
        }

        String outputDir = params.getOrDefault("outputDir", "");
        String refImagePath = params.getOrDefault("refImagePath", "");
        String tgtImagePath = params.getOrDefault("tgtImagePath", "");
        int refSeries = Integer.parseInt(params.getOrDefault("refSeries", "-1"));
        int tgtSeries = Integer.parseInt(params.getOrDefault("tgtSeries", "-1"));

        boolean refFlipped = Boolean.parseBoolean(params.getOrDefault("refFlipped", "false"));
        String refRotated = params.getOrDefault("refRotated", "90");

        String tgtChannel = params.getOrDefault("tgtChannel", "Hematoxylon");
        String tgtStain = params.getOrDefault("tgtStain", "H&E");
        float pxlSz = Float.parseFloat(params.getOrDefault("pxlSz", "0.2125"));

        // Denoising
        int rolling = Integer.parseInt(params.getOrDefault("rolling", "50"));
        float sigma = Float.parseFloat(params.getOrDefault("sigma", "12.0"));

        // SIFT parameters
        FloatArray2DSIFT.Param siftParam = new FloatArray2DSIFT.Param();
        siftParam.initialSigma = Float.parseFloat(params.getOrDefault("sift_initialSigma", "1.6"));
        siftParam.steps = Integer.parseInt(params.getOrDefault("sift_steps", "3"));
        siftParam.minOctaveSize = Integer.parseInt(params.getOrDefault("sift_minOctaveSize", "64"));
        siftParam.maxOctaveSize = Integer.parseInt(params.getOrDefault("sift_maxOctaveSize", "1024"));
        siftParam.fdSize = Integer.parseInt(params.getOrDefault("sift_fdSize", "4"));
        siftParam.fdBins = Integer.parseInt(params.getOrDefault("sift_fdBins", "8"));

        float rod = Float.parseFloat(params.getOrDefault("rod", "0.92"));
        float maxEpsilon = Float.parseFloat(params.getOrDefault("maxEpsilon", "25.0"));
        float minInlierRatio = Float.parseFloat(params.getOrDefault("minInlierRatio", "0.05"));
        int minNumInliers = Integer.parseInt(params.getOrDefault("minNumInliers", "7"));

        // bUnwarpJ parameters
        int bUnwarpJ_mode = Integer.parseInt(params.getOrDefault("bUnwarpJ_mode", "0"));
        int img_subsamp_fact = Integer.parseInt(params.getOrDefault("img_subsamp_fact", "0"));
        int min_scale_deformation = Integer.parseInt(params.getOrDefault("min_scale_deformation", "0"));
        int max_scale_deformation = Integer.parseInt(params.getOrDefault("max_scale_deformation", "3"));
        double divWeight = Double.parseDouble(params.getOrDefault("divWeight", "0.0"));
        double curlWeight = Double.parseDouble(params.getOrDefault("curlWeight", "0.0"));
        double landmarkWeight = Double.parseDouble(params.getOrDefault("landmarkWeight", "0.0"));
        double imageWeight = Double.parseDouble(params.getOrDefault("imageWeight", "1.0"));
        double consistencyWeight = Double.parseDouble(params.getOrDefault("consistencyWeight", "10.0"));
        double stopThreshold = Double.parseDouble(params.getOrDefault("stopThreshold", "0.01"));

        // Verbose mode: keep all windows open for debugging
        boolean verbose = Boolean.parseBoolean(params.getOrDefault("verbose", "false"));

        boolean headless = GraphicsEnvironment.isHeadless();
        boolean cliMode = headless &&
                          !outputDir.isEmpty() &&
                          !refImagePath.isEmpty() &&
                          !tgtImagePath.isEmpty();

        // Series indices mean different downsamples in different files, and CLI
        // callers cannot see the Bio-Formats importer, so let them look first.
        if (Boolean.parseBoolean(params.getOrDefault("listSeries", "false"))) {
            if (!refImagePath.isEmpty()) listSeries("reference (DAPI)", refImagePath);
            if (!tgtImagePath.isEmpty()) listSeries("target (H&E)", tgtImagePath);
            return;
        }

        ImagePlus refImg = null;
        ImagePlus tgtImg = null;
        List<ImagePlus> imgList = null;
        int refImgWidth = -1;
        int refImgHeight = -1;
        int refScale = 0;
        int tgtScale = 0;

        // Options strings needed in both modes
        String[] refImgRotationOptions = new String[] { "-270", "-180", "-90", "0", "90", "180", "270" };
        String[] imgSeriesOptions = new String[] { "1", "2", "3", "4", "5", "6", "7", "8" };
        String[] tgtImgChannelOptions = new String[] { "Hematoxylon", "Eosin", "Residual" };
        String[] tgtImgStainOptions = new String[] { "H&E", "H&E 2" };

        try {

            if (cliMode) {
                // -------- Headless / CLI mode: open images from paths --------
                IJ.log("ST2WSI_Registration: running in headless CLI mode.");
                IJ.log("  refImagePath = " + refImagePath);
                IJ.log("  tgtImagePath = " + tgtImagePath);
                IJ.log("  outputDir    = " + outputDir);

                if (refSeries < 1) refSeries = 3; // reasonable default
                if (tgtSeries < 1) tgtSeries = 3;

                Path dtPath = Paths.get(outputDir, "direct_transf.txt");
                Path rpPath = Paths.get(outputDir, "registration_params.json");
                if (Files.exists(dtPath) || Files.exists(rpPath)) {
                    IJ.log("WARNING: direct_transf.txt or registration_params.json already exists in " +
                           outputDir + " and will be overwritten (headless mode).");
                }

                refImg = readImage(refImagePath, refSeries - 1);
                tgtImg = readImage(tgtImagePath, tgtSeries - 1);

                if (refImg == null || tgtImg == null) {
                    throw new RuntimeException("Failed to open reference or target image from file paths.");
                }

                refImgWidth = refImg.getWidth();
                refImgHeight = refImg.getHeight();
                refScale = getScalingFactor(refImagePath, refSeries - 1);
                tgtScale = getScalingFactor(tgtImagePath, tgtSeries - 1);

                imgList = new ArrayList<>(Arrays.asList(refImg, tgtImg));

            } else {
                // -------- GUI mode: use open images + dialog --------
                int[] wList = ij.WindowManager.getIDList();
                if (wList == null || wList.length < 2) {
                    IJ.error("ST2WSI_Registration", "Need at least 2 open images");
                    return;
                }

                String[] titles = new String[wList.length];
                for (int i = 0; i < wList.length; i++) {
                    titles[i] = ij.WindowManager.getImage(wList[i]).getTitle();
                }

                NonBlockingGenericDialog gd = new NonBlockingGenericDialog(
                    "ST2WSI_Registration - Spatial Transcriptomics to WSI Alignment");

                Panel dirPanel = new Panel(new FlowLayout(FlowLayout.LEFT, 0, 0));
                dirField = new TextField(outputDir, 80);
                browseButton = new Button("Browse...");
                browseButton.addActionListener(this);
                dirPanel.add(dirField);
                dirPanel.add(browseButton);
                gd.addPanel(dirPanel);

                gd.addChoice("Reference image (e.g., DAPI):", titles, titles[0]);
                gd.addChoice("Target image (e.g., WSI):", titles, titles[1]);
                gd.addChoice("Reference image series:", imgSeriesOptions, "3");
                gd.addToSameRow();
                gd.addChoice("Target image series:", imgSeriesOptions, "3");
                gd.addCheckbox("Reference image needs to be flipped (horizontally)?", refFlipped);
                gd.addToSameRow();
                gd.addChoice("Reference image needs to be rotated?", refImgRotationOptions, refRotated);
                gd.addNumericField("Reference image pixel dimension:", pxlSz, 4, 6, "um");
                gd.addToSameRow();
                gd.addChoice("Channel of target image to be used?", tgtImgChannelOptions, tgtChannel);
                gd.addChoice("Colour deconvolution vectors:", tgtImgStainOptions, tgtStain);

                gd.addMessage("=== Denoising Parameters ===");
                gd.addNumericField("Rolling in Subtract Background:", rolling, 2);
                gd.addToSameRow();
                gd.addNumericField("sigma in Gaussian Blur:", sigma, 2);

                gd.addMessage("=== SIFT Parameters ===");
                gd.addNumericField("Initial_gaussian_blur:", siftParam.initialSigma, 2, 6, "px");
                gd.addToSameRow();
                gd.addNumericField("Steps_per_scale_octave:", siftParam.steps, 0);
                gd.addNumericField("Minimum_image_size:", siftParam.minOctaveSize, 0, 6, "px");
                gd.addToSameRow();
                gd.addNumericField("Maximum_image_size:", siftParam.maxOctaveSize, 0, 6, "px");
                gd.addNumericField("Feature_descriptor_size:", siftParam.fdSize, 0);
                gd.addToSameRow();
                gd.addNumericField("Feature_descriptor_bins:", siftParam.fdBins, 0);

                gd.addNumericField("Closest/next_ratio:", rod, 2);
                gd.addToSameRow();
                gd.addNumericField("Max_alignment_error:", maxEpsilon, 1, 6, "px");
                gd.addNumericField("Min_inlier_ratio:", minInlierRatio, 2);
                gd.addToSameRow();
                gd.addNumericField("Min_num_inliers:", minNumInliers, 0);

                gd.addMessage("=== bUnwarpJ Parameters ===");
                gd.addNumericField("Image_subsampling:", img_subsamp_fact, 0);
                gd.addToSameRow();
                gd.addNumericField("Min_scale_deform:", min_scale_deformation, 0);
                gd.addNumericField("Max_scale_deform:", max_scale_deformation, 0);
                gd.addToSameRow();
                gd.addNumericField("Divergence_weight:", divWeight, 2);
                gd.addNumericField("Curl_weight:", curlWeight, 2);
                gd.addToSameRow();
                gd.addNumericField("Landmark_weight:", landmarkWeight, 2);
                gd.addNumericField("Image_weight:", imageWeight, 2);
                gd.addToSameRow();
                gd.addNumericField("Consistency_weight:", consistencyWeight, 2);
                gd.addNumericField("Stop_threshold:", stopThreshold, 3);

                gd.addMessage("=== Output Options ===");
                gd.addCheckbox("Verbose (keep all intermediate windows open)", verbose);

                gd.showDialog();
                if (gd.wasCanceled())
                    return;

                outputDir = dirField.getText();
                if (outputDir.trim().isEmpty()) {
                    IJ.error("The output directory is empty!");
                    return;
                }

                Path dtPath = Paths.get(outputDir, "direct_transf.txt");
                Path rpPath = Paths.get(outputDir, "registration_params.json");
                if (Files.exists(dtPath) || Files.exists(rpPath)) {
                    if (!IJ.showMessageWithCancel(
                            "Overwrite?",
                            "Overwrite the existing direct_transf.txt/registration_params.json?")) {
                        return;
                    }
                }

                int refIndex = gd.getNextChoiceIndex();
                int targetIndex = gd.getNextChoiceIndex();

                refSeries = Integer.parseInt(imgSeriesOptions[gd.getNextChoiceIndex()]);
                tgtSeries = Integer.parseInt(imgSeriesOptions[gd.getNextChoiceIndex()]);
                refFlipped = gd.getNextBoolean();
                refRotated = refImgRotationOptions[gd.getNextChoiceIndex()];
                pxlSz = (float) gd.getNextNumber();
                tgtChannel = tgtImgChannelOptions[gd.getNextChoiceIndex()];
                tgtStain = tgtImgStainOptions[gd.getNextChoiceIndex()];
                rolling = (int) gd.getNextNumber();
                sigma = (float) gd.getNextNumber();
                siftParam.initialSigma = (float) gd.getNextNumber();
                siftParam.steps = (int) gd.getNextNumber();
                siftParam.minOctaveSize = (int) gd.getNextNumber();
                siftParam.maxOctaveSize = (int) gd.getNextNumber();
                siftParam.fdSize = (int) gd.getNextNumber();
                siftParam.fdBins = (int) gd.getNextNumber();
                rod = (float) gd.getNextNumber();
                maxEpsilon = (float) gd.getNextNumber();
                minInlierRatio = (float) gd.getNextNumber();
                minNumInliers = (int) gd.getNextNumber();
                img_subsamp_fact = (int) gd.getNextNumber();
                min_scale_deformation = (int) gd.getNextNumber();
                max_scale_deformation = (int) gd.getNextNumber();
                divWeight = gd.getNextNumber();
                curlWeight = gd.getNextNumber();
                landmarkWeight = gd.getNextNumber();
                imageWeight = gd.getNextNumber();
                consistencyWeight = gd.getNextNumber();
                stopThreshold = gd.getNextNumber();
                verbose = gd.getNextBoolean();

                refImg = ij.WindowManager.getImage(wList[refIndex]);
                tgtImg = ij.WindowManager.getImage(wList[targetIndex]);

                refImgWidth = refImg.getWidth();
                refImgHeight = refImg.getHeight();
                refScale = getScalingFactor(refImg.getOriginalFileInfo().getFilePath(), refSeries - 1);
                tgtScale = getScalingFactor(tgtImg.getOriginalFileInfo().getFilePath(), tgtSeries - 1);

                imgList = new ArrayList<>(Arrays.asList(refImg, tgtImg));
            }

            // -------- Common pipeline: SIFT + bUnwarpJ --------

            IJ.log("ST2WSI starts...");
            // Swapping the two paths still produces a convincing overlay, because
            // registration is near-symmetric -- but the transform then maps H&E
            // to DAPI and every downstream cell lands off-nucleus. The RGB slide
            // is the target by definition, so say so before hours are wasted.
            if (refImg.getType() == ImagePlus.COLOR_RGB
                    && tgtImg.getType() != ImagePlus.COLOR_RGB) {
                IJ.log("WARNING: the reference image is RGB and the target is not.");
                IJ.log("         refImagePath should be the DAPI/ST image and");
                IJ.log("         tgtImagePath the H&E WSI. Check the two paths:");
                IJ.log("           ref = " + refImagePath);
                IJ.log("           tgt = " + tgtImagePath);
            }
            if (WindowManager.getIDList() != null && WindowManager.getWindow("Log") != null)
                WindowManager.getWindow("Log").toFront();

            // Rotate reference image if needed
            if ("90".equals(refRotated)) {
                IJ.run(refImg, "Rotate 90 Degrees Right", "");
            } else if ("180".equals(refRotated)) {
                IJ.run(refImg, "Rotate 90 Degrees Right", "");
                IJ.run(refImg, "Rotate 90 Degrees Right", "");
            } else if ("270".equals(refRotated)) {
                IJ.run(refImg, "Rotate 90 Degrees Right", "");
                IJ.run(refImg, "Rotate 90 Degrees Right", "");
                IJ.run(refImg, "Rotate 90 Degrees Right", "");
            } else if ("-90".equals(refRotated)) {
                IJ.run(refImg, "Rotate 90 Degrees Left", "");
            } else if ("-180".equals(refRotated)) {
                IJ.run(refImg, "Rotate 90 Degrees Left", "");
                IJ.run(refImg, "Rotate 90 Degrees Left", "");
            } else if ("-270".equals(refRotated)) {
                IJ.run(refImg, "Rotate 90 Degrees Left", "");
                IJ.run(refImg, "Rotate 90 Degrees Left", "");
                IJ.run(refImg, "Rotate 90 Degrees Left", "");
            }

            if (refFlipped) {
                IJ.run(refImg, "Flip Horizontally", "");
            }

            // Pre-processing for both images
            for (int i = 0; i < imgList.size(); i++) {
                ImagePlus img = imgList.get(i);

                // Composite multi-channel to RGB
                if (img.isComposite() &&
                    (img.getNChannels() == 3 || img.getNChannels() == 4) &&
                    img.getType() == ImagePlus.GRAY8) {

                    String title = img.getTitle();
                    IJ.log(String.format("Convert %s multi-channel composite to RGB color", title));
                    IJ.run(img, "RGB Color", "");
                    img = WindowManager.getImage(title + " (RGB)");

                    if (WindowManager.getIDList() != null && WindowManager.getImage(title) != null)
                        WindowManager.getImage(title).close();
                    if (WindowManager.getIDList() != null && WindowManager.getWindow("Log") != null)
                        WindowManager.getWindow("Log").toFront();
                }

                // Color deconvolution for RGB WSI; select channel
                if (!img.isComposite() &&
                    img.getNChannels() == 1 &&
                    img.getType() == ImagePlus.COLOR_RGB) {

                    String title = img.getTitle();
                    IJ.log(String.format("Colour deconvolution for %s (vectors=%s, channel=%s)",
                                         title, tgtStain, tgtChannel));
                    if (GraphicsEnvironment.isHeadless()) {
                        ImagePlus deconv = deconvolveHEDHeadless(img, tgtChannel, tgtStain);
                        img = deconv;
                    } else {
                        IJ.run(img, "Colour Deconvolution",
                               "vectors=[" + tgtStain + "] hide legend");

                        if (tgtChannel.equals(tgtImgChannelOptions[0])) {
                            img = WindowManager.getImage(title + "-(Colour_1)");
                            if (WindowManager.getIDList() != null && WindowManager.getImage(title) != null)
                                WindowManager.getImage(title).close();
                            if (WindowManager.getIDList() != null && WindowManager.getImage(title + "-(Colour_2)") != null)
                                WindowManager.getImage(title + "-(Colour_2)").close();
                            if (WindowManager.getIDList() != null && WindowManager.getImage(title + "-(Colour_3)") != null)
                                WindowManager.getImage(title + "-(Colour_3)").close();
                        } else if (tgtChannel.equals(tgtImgChannelOptions[1])) {
                            img = WindowManager.getImage(title + "-(Colour_2)");
                            if (WindowManager.getIDList() != null && WindowManager.getImage(title) != null)
                                WindowManager.getImage(title).close();
                            if (WindowManager.getIDList() != null && WindowManager.getImage(title + "-(Colour_1)") != null)
                                WindowManager.getImage(title + "-(Colour_1)").close();
                            if (WindowManager.getIDList() != null && WindowManager.getImage(title + "-(Colour_3)") != null)
                                WindowManager.getImage(title + "-(Colour_3)").close();
                        } else if (tgtChannel.equals(tgtImgChannelOptions[2])) {
                            img = WindowManager.getImage(title + "-(Colour_3)");
                            if (WindowManager.getIDList() != null && WindowManager.getImage(title) != null)
                                WindowManager.getImage(title).close();
                            if (WindowManager.getIDList() != null && WindowManager.getImage(title + "-(Colour_2)") != null)
                                WindowManager.getImage(title + "-(Colour_2)").close();
                            if (WindowManager.getIDList() != null && WindowManager.getImage(title + "-(Colour_1)") != null)
                                WindowManager.getImage(title + "-(Colour_1)").close();
                        }
                    }

                    if (img == null) {
                        throw new RuntimeException("Colour deconvolution produced no output image");
                    }

                    IJ.run(img, "Invert", "");
                    IJ.run(img, "Grays", "");
                    if (WindowManager.getIDList() != null && WindowManager.getWindow("Log") != null)
                        WindowManager.getWindow("Log").toFront();
                }

                // Z-projection if stack
                if (img.getStackSize() > 1 &&
                    (img.getType() == ImagePlus.GRAY8 ||
                     img.getType() == ImagePlus.GRAY16 ||
                     img.getType() == ImagePlus.GRAY32)) {

                    String title = img.getTitle();
                    IJ.log(String.format("Z projection for %s", title));
                    IJ.run(img, "Z Project...", "projection=[Average Intensity]");
                    img = WindowManager.getImage("AVG_" + title);
                    IJ.run(img, "Grays", "");
                    if (WindowManager.getIDList() != null && WindowManager.getImage(title) != null)
                        WindowManager.getImage(title).close();
                    if (WindowManager.getIDList() != null && WindowManager.getWindow("Log") != null)
                        WindowManager.getWindow("Log").toFront();
                }

                // Normalize
                if (!img.isComposite() &&
                    img.getNChannels() == 1 &&
                    img.getStackSize() == 1 &&
                    (img.getType() == ImagePlus.GRAY8 ||
                     img.getType() == ImagePlus.GRAY16 ||
                     img.getType() == ImagePlus.GRAY32)) {

                    String title = img.getTitle();
                    IJ.log(String.format("Normalize %s", title));
                    IJ.run(img, "Enhance Contrast...", "saturated=0 equalize");
                    IJ.run(img, "8-bit", "");
                    IJ.run(img, "Grays", "");
                    if (WindowManager.getIDList() != null && WindowManager.getWindow("Log") != null)
                        WindowManager.getWindow("Log").toFront();
                }

                // Denoise
                if (!img.isComposite() &&
                    img.getNChannels() == 1 &&
                    img.getType() == ImagePlus.GRAY8) {

                    String title = img.getTitle();
                    IJ.log(String.format("Denoising %s", title));
                    IJ.run(img, "Subtract Background...", String.format("rolling=%d", rolling));
                    IJ.run(img, "Gaussian Blur...", String.format("sigma=%f", sigma));
                    IJ.run(img, "Enhance Contrast...", "saturated=0 equalize");
                    if (WindowManager.getIDList() != null && WindowManager.getWindow("Log") != null)
                        WindowManager.getWindow("Log").toFront();
                }

                imgList.set(i, img);
            }

            refImg = imgList.get(0);
            tgtImg = imgList.get(1);

            // SIFT feature extraction and matching
            IJ.log("Extracting SIFT features...");
            if (WindowManager.getIDList() != null)
                IJ.showStatus("Extracting SIFT features...");

            FloatArray2DSIFT sift = new FloatArray2DSIFT(siftParam);
            SIFT ijSIFT = new SIFT(sift);

            Collection<Feature> fs1 = new ArrayList<>();
            Collection<Feature> fs2 = new ArrayList<>();

            ijSIFT.extractFeatures(refImg.getProcessor(), fs1);
            ijSIFT.extractFeatures(tgtImg.getProcessor(), fs2);

            IJ.log("Reference image features: " + fs1.size());
            IJ.log("Target  image features: " + fs2.size());

            if (fs1.size() == 0 || fs2.size() == 0) {
                IJ.error("No SIFT features found in one or both images");
                return;
            }

            IJ.log("Matching features...");
            IJ.showStatus("Matching features...");
            Vector<PointMatch> candidates = new Vector<>();

            for (Feature f1 : fs1) {
                Feature best = null;
                Feature secondBest = null;
                double bestDistance = Float.MAX_VALUE;
                double secondBestDistance = Float.MAX_VALUE;

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

                if (best != null && secondBest != null &&
                    bestDistance / secondBestDistance < rod) {

                    double[] p1Local = new double[] { f1.location[0], f1.location[1] };
                    double[] p2Local = new double[] { best.location[0], best.location[1] };
                    candidates.add(
                        new PointMatch(
                            new Point(p1Local, p1Local.clone()),
                            new Point(p2Local, p2Local.clone())
                        )
                    );
                }
            }

            IJ.log("Feature matches found: " + candidates.size());
            if (candidates.size() < 4) {
                IJ.error("Insufficient feature matches found: " + candidates.size());
                return;
            }

            // Affine model with RANSAC
            AffineModel2D affineModel = new AffineModel2D();
            Vector<PointMatch> inliers = new Vector<>();

            boolean modelFound = affineModel.filterRansac(
                candidates,
                inliers,
                1000,
                maxEpsilon,
                minInlierRatio,
                minNumInliers
            );

            if (!modelFound) {
                IJ.error("SIFT Alignment", "Failed to find reliable transformation model");
                return;
            }

            IJ.log("Inliers found: " + inliers.size() + " / " + candidates.size());
            IJ.log("Inlier ratio: " +
                   String.format("%.3f", (float) inliers.size() / candidates.size()));

            double[] affineMatrix = new double[6];
            affineModel.toArray(affineMatrix);

            String siftResult = String.format(
                "Transformation Matrix: AffineTransform[[%s, %s, %s], [%s, %s, %s]]",
                BigDecimal.valueOf(affineMatrix[0]).toPlainString(),
                BigDecimal.valueOf(affineMatrix[2]).toPlainString(),
                BigDecimal.valueOf(affineMatrix[4]).toPlainString(),
                BigDecimal.valueOf(affineMatrix[1]).toPlainString(),
                BigDecimal.valueOf(affineMatrix[3]).toPlainString(),
                BigDecimal.valueOf(affineMatrix[5]).toPlainString()
            );
            IJ.log(siftResult);

            ImageProcessor refIp = refImg.getProcessor();
            ImageProcessor transformedIp = refIp.createProcessor(
                tgtImg.getWidth(),
                tgtImg.getHeight()
            );

            mpicbg.ij.InverseTransformMapping<?> mapping =
                new mpicbg.ij.InverseTransformMapping<AffineModel2D>(affineModel);

            mapping.mapInterpolated(refIp, transformedIp);

            ImagePlus refTransformedImg =
                new ImagePlus(refImg.getTitle() + "_aligned", transformedIp);

            ImageStack transformedStack = new ImageStack(
                tgtImg.getWidth(),
                tgtImg.getHeight()
            );
            transformedStack.addSlice(tgtImg.getTitle(), tgtImg.getProcessor());
            transformedStack.addSlice(
                refTransformedImg.getTitle(),
                refTransformedImg.getProcessor()
            );
            ImagePlus transformedStackImg =
                new ImagePlus("Transformed (SIFT only) result", transformedStack);
            if (!headless && WindowManager.getIDList() != null)
                transformedStackImg.show();
            // This is the transform wsitrain consumes, so QC it even without a display.
            saveAlignmentCheck(transformedStackImg, tgtImg.getProcessor(),
                               transformedIp, outputDir, "alignment_sift");
            if (WindowManager.getIDList() != null && WindowManager.getWindow("Log") != null)
                WindowManager.getWindow("Log").toFront();

            IJ.log("Performing bUnwarpJ registration...");
            if (WindowManager.getIDList() != null)
                IJ.showStatus("Performing bUnwarpJ registration...");

            bunwarpj.Transformation warp = bUnwarpJ_.computeTransformationBatch(
                tgtImg,
                refTransformedImg,
                null,
                null,
                bUnwarpJ_mode,
                img_subsamp_fact,
                min_scale_deformation,
                max_scale_deformation,
                divWeight,
                curlWeight,
                landmarkWeight,
                imageWeight,
                consistencyWeight,
                stopThreshold
            );

            if (warp == null) {
                IJ.error("bUnwarpJ Registration",
                         "Registration failed - could not compute transformation.");
                return;
            }

            double[][] cx_direct = warp.getDirectDeformationCoefficientsX();
            double[][] cy_direct = warp.getDirectDeformationCoefficientsY();
            int intervals = warp.getIntervals();

            // applyTransformationMT writes in place, and the SIFT-only stack holds this
            // same ImageProcessor -- without a copy both result windows show the warp.
            ImagePlus refTransformedWarpedImg = new ImagePlus(
                refTransformedImg.getTitle() + "_warped",
                refTransformedImg.getProcessor().duplicate()
            );
            BSplineModel warpModel = new BSplineModel(
                refTransformedWarpedImg.getProcessor(),
                false,
                img_subsamp_fact
            );
            // bUnwarpJ splits the rows over the processors and fails on an odd height with many
            // processors (negative block height): give it a target one row taller in that case.
            ImagePlus warpTarget = tgtImg.getHeight() % 2 == 0 ? tgtImg
                : new ImagePlus("target", new ij.process.ByteProcessor(tgtImg.getWidth(), tgtImg.getHeight() + 1));
            // the result is the returned processor; the input is not modified
            ImageProcessor warpedFull = bunwarpj.MiscTools.applyTransformationMT(
                refTransformedWarpedImg,
                warpTarget,
                warpModel,
                intervals,
                cx_direct,
                cy_direct
            );
            warpedFull.setRoi(0, 0, tgtImg.getWidth(), tgtImg.getHeight());
            ImageProcessor warpedProcessor = warpedFull.crop();
            refTransformedWarpedImg.setProcessor(warpedProcessor);

            ImageStack transformedWarpedStack = new ImageStack(
                warpedProcessor.getWidth(),
                warpedProcessor.getHeight()
            );
            transformedWarpedStack.addSlice(tgtImg.getTitle(), tgtImg.getProcessor());
            transformedWarpedStack.addSlice(
                refTransformedWarpedImg.getTitle(),
                warpedProcessor
            );
            ImagePlus transformedWarpedStackImg = new ImagePlus(
                "Transformed/Warped (SIFT/bUnwarpJ) result",
                transformedWarpedStack
            );
            if (!headless && WindowManager.getIDList() != null)
                transformedWarpedStackImg.show();
            saveAlignmentCheck(transformedWarpedStackImg, tgtImg.getProcessor(),
                               refTransformedWarpedImg.getProcessor(), outputDir,
                               "alignment_bunwarpj");
            if (WindowManager.getIDList() != null && WindowManager.getWindow("Log") != null)
                WindowManager.getWindow("Log").toFront();

            // Build JSON params
            JSONArray jsonAffineMatrix = new JSONArray();
            jsonAffineMatrix.put(BigDecimal.valueOf(affineMatrix[0]).toPlainString());
            jsonAffineMatrix.put(BigDecimal.valueOf(affineMatrix[2]).toPlainString());
            jsonAffineMatrix.put(BigDecimal.valueOf(affineMatrix[4]).toPlainString());
            jsonAffineMatrix.put(BigDecimal.valueOf(affineMatrix[1]).toPlainString());
            jsonAffineMatrix.put(BigDecimal.valueOf(affineMatrix[3]).toPlainString());
            jsonAffineMatrix.put(BigDecimal.valueOf(affineMatrix[5]).toPlainString());

            JSONObject jsonObj = new JSONObject();
            jsonObj.put("xnumAnnotImgRegParamSrcImgWidth", refImgWidth * refScale);
            jsonObj.put("xnumAnnotImgRegParamSrcImgHeight", refImgHeight * refScale);
            jsonObj.put("xnumAnnotImgRegParamFlipHori", refFlipped);
            jsonObj.put("xnumAnnotImgRegParamFlipVert", false);
            jsonObj.put("xnumAnnotImgRegParamDapiImgPxlSize", pxlSz);
            jsonObj.put("xnumAnnotImgRegParamRotation", refRotated);
            jsonObj.put("xnumAnnotImgRegParamSiftMatrix", jsonAffineMatrix);
            jsonObj.put("xnumAnnotImgRegParamSourceScale", refScale);
            jsonObj.put("xnumAnnotImgRegParamTargetScale", tgtScale);

            try {
                String affineMtxFilePath =
                    Paths.get(outputDir, "registration_params.json").toString();
                FileWriter file = new FileWriter(affineMtxFilePath);
                file.write(jsonObj.toString());
                file.flush();
                file.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

            bunwarpj.MiscTools.saveElasticTransformation(
                intervals,
                cx_direct,
                cy_direct,
                Paths.get(outputDir, "direct_transf.txt").toString()
            );


            // Auto-close intermediate windows unless verbose mode is enabled
            if (!headless && !verbose && WindowManager.getIDList() != null) {
                // Close preprocessed reference and target images
                if (refImg != null && refImg.getWindow() != null) {
                    refImg.changes = false;  // prevent save prompt
                    refImg.close();
                }
                if (tgtImg != null && tgtImg.getWindow() != null) {
                    tgtImg.changes = false;
                    tgtImg.close();
                }
                // Close SIFT-only result, keep only final bUnwarpJ result
                if (transformedStackImg != null && transformedStackImg.getWindow() != null) {
                    transformedStackImg.changes = false;
                    transformedStackImg.close();
                }
            }
            
            IJ.log("ST2WSI registration completed successfully.");
            IJ.log("registration_params.json/direct_transf.txt saved to: " + outputDir);

            if (!headless && WindowManager.getIDList() != null) {
                IJ.showMessage(
                    "ST2WSI Registration",
                    "ST2WSI registration completed!\n" +
                    "registration_params.json/direct_transf.txt saved to:\n" +
                    outputDir
                );
            }

            if (WindowManager.getIDList() != null)
                IJ.showStatus("ST2WSI alignment pipeline completed successfully.");

        } catch (Exception e) {
            IJ.error(
                "Pipeline Error",
                "ST2WSI alignment pipeline failed: " + e.getMessage() +
                "\nTry image series of higher resoliution."
            );
            e.printStackTrace();
        }
    }

    /* ------------------------------------------------------------------
     * Standalone main (optional)
     * ------------------------------------------------------------------ */

    public static void main(String[] args) {
        String joinArgs = String.join(" ", args);
        ST2WSI_Registration inst = new ST2WSI_Registration();
        inst.run(joinArgs);
        System.exit(0);
    }

    /* ------------------------------------------------------------------
     * Browse button handler (GUI only)
     * ------------------------------------------------------------------ */

    @Override
    public void actionPerformed(ActionEvent e) {
        if (e.getSource() == browseButton) {
            DirectoryChooser dc = new DirectoryChooser("Output Directlory");
            String dir = dc.getDirectory();
            if (dir != null) {
                dirField.setText(dir);
            }
        }
    }

}