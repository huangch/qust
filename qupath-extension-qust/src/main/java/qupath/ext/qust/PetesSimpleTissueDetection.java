/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2023 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.ext.qust;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.locationtech.jts.densify.Densifier;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.plugin.filter.RankFilters;
import ij.process.Blitter;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import qupath.imagej.processing.MorphologicalReconstruction;
import qupath.imagej.processing.RoiLabeling;
import qupath.imagej.processing.SimpleThresholding;
import qupath.imagej.tools.IJTools;
import qupath.lib.analysis.images.ContourTracing;
import qupath.lib.analysis.images.SimpleImage;
import qupath.lib.analysis.images.SimpleImages;
import qupath.lib.common.GeneralTools;
import qupath.lib.geom.Point2;
import qupath.lib.images.ImageData;
import qupath.lib.images.PathImage;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.PathRootObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractDetectionPlugin;
import qupath.lib.plugins.DetectionPluginTools;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.PolygonROI;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.ShapeSimplifier;
import qupath.lib.roi.interfaces.ROI;

/**
 * Very basic global thresholding command to identify tissue regions.
 * <p>
 * Based on QuPath's {@code SimpleTissueDetection2} by Pete Bankhead. Like the original it uses a fixed approach to
 * downsampling images and a global threshold applied to RGB images only. The conversion of the thresholded image to
 * regions is different: the original traces the regions with ImageJ's wand and combines them with Java's {@code Area},
 * which on real slides gave outlines with long horizontal edges that cut through the tissue and left out parts of it.
 * Here the regions are traced with QuPath's {@link ContourTracing} and refined (small pieces, holes, smoothing) with JTS,
 * so the outline follows the thresholded image exactly.
 * <p>
 * TODO: Provide choice of channels to threshold
 * TODO: Support 16-bit data
 * 
 * @author Pete Bankhead
 *
 */
public class PetesSimpleTissueDetection extends AbstractDetectionPlugin<BufferedImage> {
	
	private static final Logger logger = LoggerFactory.getLogger(PetesSimpleTissueDetection.class);
	
	private ParameterList params;

	private String lastResults = null;
	
	/**
	 * Constructor.
	 */
	public PetesSimpleTissueDetection() {
		
		params = new ParameterList().
				addIntParameter("threshold", "Threshold", 127, null, 0, 255, "Global threshold to use - defined in the range 0-255");
		
		params.addDoubleParameter("requestedPixelSizeMicrons", "Requested pixel size", 20, GeneralTools.micrometerSymbol(), "Requested pixel size for detection resolution - higher values mean a less detailed (but faster) result.\nNote that if the resolution is set too high (leading to a huge image) it will be adjusted automatically.");
		params.addDoubleParameter("minAreaMicrons", "Minimum area", 10000, GeneralTools.micrometerSymbol()+"^2", "The minimum area for a detected region - smaller regions will be discarded");
		params.addDoubleParameter("maxHoleAreaMicrons", "Max fill area", 1000000, GeneralTools.micrometerSymbol()+"^2", "'Holes' occurring within the detected regions that are smaller than this will be filled in");
		
		params.addDoubleParameter("requestedDownsample", "Downsample", 50, null, "Amount to downsample the image - higher values indicate smaller images (and less detection).\nNote that if the downsample is set too low (leading to a huge image) it will be adjusted automatically.");
		params.addDoubleParameter("minAreaPixels", "Minimum area", 100000, "px^2", "The minimum area for a detected region - smaller regions will be discarded");
		params.addDoubleParameter("maxHoleAreaPixels", "Max fill area", 500, "px^2", "'Holes' occurring within the detected regions that are smaller than this will be filled in");


		params.addBooleanParameter("darkBackground", "Dark background", false, "Choose this option if the background is darker (e.g. in a fluorescence image) rather than brighter than the tissue");
		params.addBooleanParameter("smoothImage", "Smooth image", true, "Apply 3x3 mean filter to (downsampled) image to reduce noise before thresholding");
		params.addBooleanParameter("medianCleanup", "Cleanup with median filter", true, "Apply median filter to thresholded image to reduce small variations");
		params.addBooleanParameter("dilateBoundaries", "Expand boundaries", false, "Apply 3x3 maximum filter to binary image to increase region sizes");
		params.addBooleanParameter("smoothCoordinates", "Smooth coordinates", true, "Apply smoothing to region boundaries, to reduce 'blocky' appearance");
		params.addBooleanParameter("excludeOnBoundary", "Exclude on boundary", false, "Discard detection regions that touch the image boundary");
		
		params.addBooleanParameter("singleAnnotation", "Single annotation", false, "Create a single annotation object from all (possibly-disconnected) regions.\nOff (default): one annotation per piece of tissue. QuPath draws an annotation that consists of several pieces with extra horizontal lines across it (the region itself is right), so the pieces are better kept apart.");

	}
	
	
	
	class GlobalThresholder implements ObjectDetector<BufferedImage> {
		
		@Override
		public Collection<PathObject> runDetection(final ImageData<BufferedImage> imageData, final ParameterList params, final ROI pathROI) throws IOException {
			
			ImageServer<BufferedImage> server = imageData.getServer();
			
			double downsample;
			PixelCalibration cal = server.getPixelCalibration();
			if (cal.hasPixelSizeMicrons()) {
				downsample = params.getDoubleParameterValue("requestedPixelSizeMicrons") / cal.getAveragedPixelSizeMicrons();
			} else
				downsample = params.getDoubleParameterValue("requestedDownsample");
			
			double maxDim = pathROI == null ? Math.max(server.getWidth(), server.getHeight()) : Math.max(pathROI.getBoundsWidth(), pathROI.getBoundsHeight());
			double maxDimLimit = 4000;
			if (!(maxDim / downsample <= maxDimLimit)) {
				logger.warn("Invalid requested downsample {} - will use {} instead", downsample, maxDim / maxDimLimit);
				downsample = maxDim / maxDimLimit;
			}
			
			RegionRequest request;
			if (!RoiTools.isShapeROI(pathROI)) {
				request = RegionRequest.createInstance(server.getPath(), downsample, 0, 0, server.getWidth(), server.getHeight());
			} else
				request = RegionRequest.createInstance(server.getPath(), downsample, pathROI);

			PathImage<ImagePlus> pathImage = IJTools.convertToImagePlus(server, request); // TODO: Implement z-stack support
			
			double threshold = params.getIntParameterValue("threshold");
			double minAreaMicrons = 1, maxHoleAreaMicrons = 1, minAreaPixels = 1, maxHoleAreaPixels = 1;
			if (cal.hasPixelSizeMicrons()) {
				minAreaMicrons = params.getDoubleParameterValue("minAreaMicrons");
				maxHoleAreaMicrons = params.getDoubleParameterValue("maxHoleAreaMicrons");
			} else {
				minAreaPixels = params.getDoubleParameterValue("minAreaPixels");
				maxHoleAreaPixels = params.getDoubleParameterValue("maxHoleAreaPixels");			
			}
			boolean smoothImage = params.getBooleanParameterValue("smoothImage");
			boolean darkBackground = params.getBooleanParameterValue("darkBackground");
			boolean smoothCoordinates = params.getBooleanParameterValue("smoothCoordinates");
			boolean medianCleanup = params.getBooleanParameterValue("medianCleanup");
			boolean excludeOnBoundary = params.getBooleanParameterValue("excludeOnBoundary");
			boolean dilateBoundaries = params.getBooleanParameterValue("dilateBoundaries");
			boolean singleAnnotation = Boolean.TRUE.equals(params.getBooleanParameterValue("singleAnnotation"));
			
			// Create a ByteProcessor
			ImagePlus imp = pathImage.getImage();
			ByteProcessor bp = imp.getProcessor().convertToByteProcessor();
			
			if (smoothImage)
				bp.smooth();
	
			// Apply threshold
			if (darkBackground)
				bp = SimpleThresholding.thresholdAbove(bp, (float)threshold);
			else
				bp = SimpleThresholding.thresholdBelow(bp, (float)threshold);
					
			if (Thread.currentThread().isInterrupted())
				return null;

			
			// Apply small median filter to clean up
			if (medianCleanup) {
				RankFilters rf = new RankFilters();
				rf.rank(bp, 1, RankFilters.MEDIAN);
			}
			
			// Apply maximum filter, if required
			if (dilateBoundaries)
				bp.filter(ImageProcessor.MAX);
			
			// If there is a ROI, clear everything outside
			Roi roiIJ = null;
			if (pathROI != null) {
				roiIJ = IJTools.convertToIJRoi(pathROI, imp.getCalibration(), downsample);
				RoiLabeling.clearOutside(bp, roiIJ);
			}
			
			// Exclude on image boundary now, if required
			// This needs to be done before getting ROIs, because filled ROIs are requested - which can result in the whole image being filled in if the boundary covers everything
			if (excludeOnBoundary) {
				ByteProcessor bpMarker = new ByteProcessor(bp.getWidth(), bp.getHeight());
				bpMarker.setValue(255);
				bpMarker.drawRect(0, 0, bp.getWidth(), bp.getHeight());
				if (roiIJ != null)
					bpMarker.draw(roiIJ);
				bpMarker.copyBits(bp, 0, 0, Blitter.AND);
				ByteProcessor bpBoundary = MorphologicalReconstruction.binaryReconstruction(bpMarker, bp, false);
				bp.copyBits(bpBoundary, 0, 0, Blitter.SUBTRACT);
			}
			
			if (Thread.currentThread().isInterrupted())
				return null;
			
			// Convert to objects
			double minArea, maxHoleArea;
			if (cal.hasPixelSizeMicrons()) {
				double areaScale = 1.0 / (cal.getAveragedPixelSizeMicrons() * cal.getAveragedPixelSizeMicrons() * downsample * downsample);
				minArea = minAreaMicrons * areaScale;
				maxHoleArea = maxHoleAreaMicrons * areaScale;
			} else {
				minArea = minAreaPixels / (downsample * downsample);
				maxHoleArea = maxHoleAreaPixels / (downsample * downsample);			
			}
			logger.debug("Min area: {}", minArea);
			logger.debug("Max hole area: {}", maxHoleArea);
			
			if (Thread.currentThread().isInterrupted())
				return null;
			
			List<PathObject> pathObjects = traceTissue(bp, request, pathROI, server, minArea,
					maxHoleArea, smoothCoordinates, singleAnnotation, request.getImagePlane());

			if (Thread.currentThread().isInterrupted())
				return null;
			
			if (pathObjects.isEmpty())
				lastResults =  "No regions detected!";
			else if (pathObjects.size() == 1)
				lastResults =  "1 region detected";
			else
				lastResults =  pathObjects.size() + " regions detected";
			
			return pathObjects;
		}
		
		
		@Override
		public String getLastResultsDescription() {
			return lastResults;
		}
		
		
	}
	
	
	/**
	 * Turns the thresholded (downsampled) image into annotations.
	 * <p>
	 * The tissue (pixels of 127 or more) is traced with {@link ContourTracing}, which follows the pixel boundaries exactly;
	 * then small pieces are dropped, small holes are filled and, if requested, the staircase outline is smoothed. All of
	 * that is done in the pixels of the thresholded image, and the result is scaled to the pixels of the full image,
	 * cut at the borders of the image and of the parent object.
	 * 
	 * @param bp thresholded image: tissue is 255, background is 0
	 * @param region the region of the full image that bp shows
	 * @param parentROI the ROI that the detection is limited to, or null
	 * @param minArea smallest region to keep, in pixels of bp
	 * @param maxHoleArea holes smaller than this are filled, in pixels of bp; if it is 0 or less every hole is filled
	 */
	private static List<PathObject> traceTissue(ByteProcessor bp, RegionRequest region, ROI parentROI, ImageServer<BufferedImage> server,
			double minArea, double maxHoleArea, boolean smoothCoordinates, boolean singleAnnotation, ImagePlane plane) {
		
		int w = bp.getWidth(), h = bp.getHeight();
		float[] pixels = new float[w * h];
		for (int i = 0; i < pixels.length; i++)
			pixels[i] = bp.get(i);
		SimpleImage image = SimpleImages.createFloatImage(pixels, w, h);
		RegionRequest maskRegion = RegionRequest.createInstance(region.getPath(), 1.0, 0, 0, w, h, region.getZ(), region.getT());
		ROI traced = ContourTracing.createTracedROI(image, 127, 255, maskRegion);
		if (traced == null || traced.isEmpty())
			return Collections.emptyList();
		
		// Small pieces are dropped, small holes are filled, bigger holes stay
		Geometry geometry = GeometryTools.refineAreas(traced.getGeometry(), minArea, maxHoleArea > 0 ? maxHoleArea : Double.POSITIVE_INFINITY);
		if (geometry == null || geometry.isEmpty())
			return Collections.emptyList();
		// a piece that lay inside a hole that has been filled now overlaps its surroundings
		geometry = UnaryUnionOp.union(geometry);
		
		if (smoothCoordinates) {
			geometry = smoothGeometry(geometry);
			// half a pixel of the full image per pixel of bp: the tolerance that the original used
			geometry = TopologyPreservingSimplifier.simplify(geometry, 0.5);
			if (!geometry.isValid())
				geometry = geometry.buffer(0);
		}
		
		// From the pixels of bp to the pixels of the full image
		double scaleX = region.getWidth() / (double) w, scaleY = region.getHeight() / (double) h;
		geometry = new AffineTransformation(scaleX, 0, region.getX(), 0, scaleY, region.getY()).transform(geometry);
		
		// The last row and column of bp can reach over the border of the image
		GeometryFactory factory = geometry.getFactory();
		geometry = geometry.intersection(factory.toGeometry(new Envelope(0, server.getWidth(), 0, server.getHeight())));
		if (parentROI != null && RoiTools.isShapeROI(parentROI))
			geometry = geometry.intersection(parentROI.getGeometry());
		geometry = GeometryTools.ensurePolygonal(geometry);
		if (geometry == null || geometry.isEmpty())
			return Collections.emptyList();
		
		List<PathObject> pathObjects = new ArrayList<>();
		if (singleAnnotation && geometry.getNumGeometries() > 1) {
			// A ROI of several polygons is drawn by QuPath through a java.awt.geom.Area, which adds horizontal lines
			// across the tissue to the outline of a complex shape (the area itself is right).
			pathObjects.add(PathObjects.createAnnotationObject(GeometryTools.geometryToROI(geometry, plane)));
		} else {
			for (int i = 0; i < geometry.getNumGeometries(); i++) {
				if (geometry.getGeometryN(i) instanceof Polygon polygon)
					pathObjects.add(PathObjects.createAnnotationObject(polygonToROI(polygon, plane)));
			}
		}
		for (PathObject pathObject : pathObjects)
			((PathAnnotationObject)pathObject).setLocked(true);
		return pathObjects;
	}
	
	
	/**
	 * A ROI for one polygon that QuPath draws exactly as it is: a polygon without holes becomes a {@link PolygonROI} (the
	 * conversion of such a polygon by {@link GeometryTools#geometryToROI(Geometry, ImagePlane)} goes through an AWT Area,
	 * which changes the outline), a polygon with holes becomes a geometry ROI.
	 */
	private static ROI polygonToROI(Polygon polygon, ImagePlane plane) {
		if (polygon.getNumInteriorRing() > 0)
			return GeometryTools.geometryToROI(polygon, plane);
		Coordinate[] coordinates = polygon.getExteriorRing().getCoordinates();
		List<Point2> points = new ArrayList<>(coordinates.length - 1);
		for (int i = 0; i < coordinates.length - 1; i++)
			points.add(new Point2(coordinates[i].x, coordinates[i].y));
		return ROIs.createPolygonROI(points, plane);
	}
	
	
	/**
	 * Smooths the corners of the outlines of all polygons. A polygon whose smoothed outline is not valid (it crosses itself
	 * or its holes) keeps the original outline.
	 */
	private static Geometry smoothGeometry(Geometry geometry) {
		List<Geometry> polygons = new ArrayList<>();
		for (int i = 0; i < geometry.getNumGeometries(); i++) {
			if (!(geometry.getGeometryN(i) instanceof Polygon polygon))
				continue;
			Polygon smoothed = smoothPolygon(polygon);
			polygons.add(smoothed.isValid() ? smoothed : polygon);
		}
		if (polygons.isEmpty())
			return geometry;
		// smoothed polygons that now touch each other are merged
		return UnaryUnionOp.union(polygons);
	}
	
	private static Polygon smoothPolygon(Polygon polygon) {
		GeometryFactory factory = polygon.getFactory();
		LinearRing shell = smoothRing(factory, polygon.getExteriorRing());
		LinearRing[] holes = new LinearRing[polygon.getNumInteriorRing()];
		for (int i = 0; i < holes.length; i++)
			holes[i] = smoothRing(factory, polygon.getInteriorRingN(i));
		return factory.createPolygon(shell, holes);
	}
	
	private static LinearRing smoothRing(GeometryFactory factory, LineString ring) {
		// The outline of the mask has a vertex only where it turns, so long straight edges have none. QuPath's smoothing
		// averages each vertex with its two neighbours, which would pull the ends of a long edge far inwards. With a vertex
		// every pixel only the corners move, by a fraction of a pixel.
		Coordinate[] coordinates = Densifier.densify(ring, 1.0).getCoordinates();
		int n = coordinates.length - 1; // the last coordinate is the first again
		if (n < 4)
			return factory.createLinearRing(ring.getCoordinates());
		List<Point2> points = new ArrayList<>(n);
		for (int i = 0; i < n; i++)
			points.add(new Point2(coordinates[i].x, coordinates[i].y));
		for (int pass = 0; pass < 2; pass++)
			points = ShapeSimplifier.smoothPoints(points);
		Coordinate[] result = new Coordinate[points.size() + 1];
		for (int i = 0; i < points.size(); i++)
			result[i] = new Coordinate(points.get(i).getX(), points.get(i).getY());
		result[result.length - 1] = new Coordinate(result[0]);
		return factory.createLinearRing(result);
	}
	

	@Override
	public ParameterList getDefaultParameterList(final ImageData<BufferedImage> imageData) {
		boolean micronsKnown = imageData.getServer().getPixelCalibration().hasPixelSizeMicrons();
		params.setHiddenParameters(!micronsKnown, "requestedPixelSizeMicrons", "minAreaMicrons", "maxHoleAreaMicrons");
		params.setHiddenParameters(micronsKnown, "requestedDownsample", "minAreaPixels", "maxHoleAreaPixels");
		return params;
	}

	@Override
	public String getName() {
		return "Simple tissue detection";
	}

	@Override
	public String getLastResultsDescription() {
		return lastResults;
	}


	@Override
	public String getDescription() {
		return "Detect one or more regions of interest by applying a global threshold";
	}


	@Override
	protected void addRunnableTasks(ImageData<BufferedImage> imageData, PathObject parentObject, List<Runnable> tasks) {
		tasks.add(DetectionPluginTools.createRunnableTask(new GlobalThresholder(), getParameterList(imageData), imageData, parentObject));
	}


	@Override
	protected Collection<? extends PathObject> getParentObjects(final ImageData<BufferedImage> imageData) {
		
		PathObjectHierarchy hierarchy = imageData.getHierarchy();
		if (hierarchy.getTMAGrid() == null)
			return Collections.singleton(hierarchy.getRootObject());
		
		return hierarchy.getSelectionModel().getSelectedObjects().stream().filter(p -> p.isTMACore()).toList();
	}


	@Override
	public Collection<Class<? extends PathObject>> getSupportedParentObjectClasses() {
		// TODO: Re-allow taking an object as input in order to limit bounds
		// Temporarily disabled so as to avoid asking annoying questions when run repeatedly
		List<Class<? extends PathObject>> list = new ArrayList<>();
		list.add(TMACoreObject.class);
		list.add(PathRootObject.class);
		return list;
	}

}
