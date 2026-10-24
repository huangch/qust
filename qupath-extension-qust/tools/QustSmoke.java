import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.stage.Window;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.roi.ROIs;
import qupath.lib.scripting.QP;

/**
 * Starts the real QuPath with the QuST extension on the class path and runs the analysis and export commands on a
 * small synthetic slide. Run it with tools/smoke.sh.
 */
public class QustSmoke {
	static int failures = 0;

	static void say(String s) {
		System.out.println("HARNESS: " + s);
		System.out.flush();
	}

	static void check(String what, boolean ok) {
		say((ok ? "PASS " : "FAIL ") + what);
		if (!ok)
			failures++;
	}

	static <T> T fx(Supplier<T> s) throws Exception {
		CompletableFuture<T> f = new CompletableFuture<>();
		Platform.runLater(() -> {
			try {
				f.complete(s.get());
			} catch (Throwable t) {
				f.completeExceptionally(t);
			}
		});
		return f.get(120, TimeUnit.SECONDS);
	}

	static List<String> items(QuPathGUI gui, String path) throws Exception {
		return fx(() -> {
			Menu m = gui.getMenu(path, false);
			return m == null ? List.<String>of() : m.getItems().stream().map(MenuItem::getText).toList();
		});
	}

	public static void main(String[] args) throws Exception {
		String out = args[0];
		Thread t = new Thread(() -> qupath.QuPath.main(new String[0]), "qupath-main");
		t.setDaemon(true);
		t.start();
		QuPathGUI gui = null;
		for (int i = 0; i < 300 && (gui = QuPathGUI.getInstance()) == null; i++)
			Thread.sleep(200);
		if (gui == null) {
			say("QuPath did not start");
			System.exit(2);
		}
		Thread.sleep(6000);
		final QuPathGUI g = gui;

		String root = "Extensions>QuST Analysis Toolbox";
		List<String> top = fx(() -> g.getMenu(root, false).getItems().stream().map(MenuItem::getText).toList());
		say("menu: " + top);
		check("four submenus", top.size() == 4);
		check("import commands", items(gui, root + ">Import...").size() == 5);
		check("preparation commands", items(gui, root + ">Preparation...").size() == 7);
		check("analysis commands", items(gui, root + ">Analysis...").size() == 6);
		check("classification commands", items(gui, root + ">Classification and Segmentation...").size() == 4);

		// A 1200 x 1200 H&E-like slide with two cell types
		BufferedImage img = new BufferedImage(1200, 1200, BufferedImage.TYPE_3BYTE_BGR);
		Random rnd = new Random(1);
		for (int y = 0; y < 1200; y++)
			for (int x = 0; x < 1200; x++)
				img.setRGB(x, y, (230 + rnd.nextInt(20)) << 16 | (150 + rnd.nextInt(40)) << 8 | (200 + rnd.nextInt(40)));
		WrappedBufferedImageServer server = new WrappedBufferedImageServer("synthetic", img);
		server.setMetadata(new qupath.lib.images.servers.ImageServerMetadata.Builder(server.getMetadata())
				.pixelSizeMicrons(0.2125, 0.2125).build());
		ImageData<BufferedImage> data = new ImageData<>(server);
		PathObject annotation = PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 1200, 1200, null));
		List<PathObject> cells = new ArrayList<>();
		for (int i = 0; i < 30; i++)
			for (int j = 0; j < 30; j++) {
				boolean tumour = j < 15 || (i + j) % 7 == 0;
				PathObject c = PathObjects.createDetectionObject(
						ROIs.createEllipseROI(100 + i * 32 + rnd.nextInt(6), 100 + j * 32 + rnd.nextInt(6), 14, 14, null),
						PathClass.fromString(tumour ? "Tumor" : "Immune"));
				c.getMeasurementList().put("GeneA", tumour ? 5 + rnd.nextInt(5) : rnd.nextInt(2));
				c.getMeasurementList().put("GeneB", tumour ? rnd.nextInt(2) : 5 + rnd.nextInt(5));
				c.getMeasurementList().put("transcript:CEACAM6", tumour ? 5 + rnd.nextInt(5) : 0);
				c.getMeasurementList().put("transcript:EGFR", tumour ? rnd.nextInt(2) : 3 + rnd.nextInt(5));
				c.getMeasurementList().put("Cell: Max diameter µm", 3.2);
				c.getMeasurementList().put("Cell: Min diameter µm", 2.8);
				cells.add(c);
			}
		annotation.addChildObjects(cells);
		data.getHierarchy().addObject(annotation);
		fx(() -> {
			try {
				g.getViewer().setImageData(data);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
			data.getHierarchy().getSelectionModel().setSelectedObject(annotation);
			return null;
		});
		Thread.sleep(1500);

		browseButtons(g, root);
		pathPreferences(g);

		Thread.sleep(500);
		runPlugin("Neighboring Cell Type Composition", "qupath.ext.qust.NeighboringCellTypeComposition", "{\"layers\":1,\"normalize\":true}", data);
		List<String> measurements = new ArrayList<>(cells.get(450).getMeasurementList().getMeasurementNames());
		say("measurements after composition: " + measurements);
		check("composition measurements written", measurements.stream().anyMatch(n -> n.toLowerCase().contains("tumor")));

		runPlugin("Cell Spatial Profiling by Classification", "qupath.ext.qust.CellSpatialProfilingByClassification",
				"{\"tgtCls\":\"Tumor\",\"type\":\"detection\",\"id\":\"smoke\",\"layers\":0}", data);
		measurements = new ArrayList<>(cells.get(450).getMeasurementList().getMeasurementNames());
		long profiled = cells.stream().filter(c -> c.getMeasurementList().getMeasurementNames().stream().anyMatch(n -> n.startsWith("csp:"))).count();
		say("cells with profiling measurements: " + profiled + " (e.g. " + measurements + ")");
		check("profiling measurements written", profiled > 0);

		qupath.ext.qust.QuSTSetup.getInstance().setCciDatasetLocationPath(args[1]);
		runPlugin("Cell-Cell Interaction Analysis", "qupath.ext.qust.CellCellInteractionAnalysis",
				"{\"layers\":1,\"ligand_receptor\":\"ligand\",\"scale\":1000.0}", data);
		long cci = cells.stream().filter(c -> c.getMeasurementList().getMeasurementNames().stream().anyMatch(n -> n.startsWith("cci:"))).count();
		say("cells with CCI measurements: " + cci);
		check("cell-cell interaction measurements written", cci > 0);

		runPlugin("Cell Spatial Density", "qupath.ext.qust.CellSpatialDensity",
				"{\"tgtCls\":\"Tumor\",\"type\":\"detection\",\"bandwidth\":60.0,\"id\":\"smokeden\"}", data);
		double tumourDensity = cells.get(450).getMeasurementList().get("csd:smokeden");
		double immuneDensity = cells.get(899).getMeasurementList().get("csd:smokeden");
		say("density of Tumor cells at a tumour cell / at an immune cell: " + tumourDensity + " / " + immuneDensity);
		check("tumour density is higher inside the tumour", tumourDensity > immuneDensity && immuneDensity >= 0);

		PathObject probe = cells.get(450);
		double rawC = probe.getMeasurementList().get("transcript:CEACAM6");
		double rawE = probe.getMeasurementList().get("transcript:EGFR");
		runPlugin("Normalize Transcripts", "qupath.ext.qust.TranscriptNormalization", "{\"targetSum\":100.0,\"log1p\":false,\"minTranscripts\":0}", data);
		double nc = probe.getMeasurementList().get("transcript-norm:CEACAM6");
		double ne = probe.getMeasurementList().get("transcript-norm:EGFR");
		say("normalized to 100: " + nc + " + " + ne + " (raw " + rawC + ", " + rawE + ")");
		check("normalized values add up to the total", Math.abs(nc + ne - 100.0) < 1e-4);
		check("raw counts unchanged", probe.getMeasurementList().get("transcript:CEACAM6") == rawC && probe.getMeasurementList().get("transcript:EGFR") == rawE);
		runPlugin("Normalize Transcripts (log)", "qupath.ext.qust.TranscriptNormalization", "{\"targetSum\":100.0,\"log1p\":true,\"minTranscripts\":0}", data);
		say("after log: " + probe.getMeasurementList().get("transcript-norm:CEACAM6") + " expected " + Math.log1p(100.0 * rawC / (rawC + rawE)));
		check("log(1 + x) applied", Math.abs(probe.getMeasurementList().get("transcript-norm:CEACAM6") - Math.log1p(100.0 * rawC / (rawC + rawE))) < 1e-5);
		runPlugin("Cell-Cell Interaction Analysis (normalized)", "qupath.ext.qust.CellCellInteractionAnalysis",
				"{\"layers\":1,\"ligand_receptor\":\"ligand\",\"scale\":1000.0,\"expression\":\"normalized\"}", data);
		long cciNorm = cells.stream().filter(c -> c.getMeasurementList().getMeasurementNames().stream().anyMatch(n -> n.startsWith("cci-norm:"))).count();
		say("cells with cci-norm measurements: " + cciNorm);
		check("cell-cell interaction on the normalized values", cciNorm > 0);

		// Delaunay connections: all links, then only the short ones, then only between cells of the same class
		runPlugin("Delaunay Connections", "qupath.ext.qust.DelaunayConnections", "{\"maxDistance\":0.0,\"sameClass\":false}", data);
		var connectionsAll = (qupath.lib.objects.PathObjectConnections) data.getProperty("OBJECT_CONNECTIONS");
		check("Delaunay connections stored", connectionsAll != null && !connectionsAll.getConnections(cells.get(450)).isEmpty());
		double linksAll = cells.stream().mapToInt(c -> connectionsAll.getConnections(c).size()).average().orElse(0);
		runPlugin("Delaunay Connections (short links)", "qupath.ext.qust.DelaunayConnections", "{\"maxDistance\":7.0,\"sameClass\":false}", data);
		var connectionsShort = (qupath.lib.objects.PathObjectConnections) data.getProperty("OBJECT_CONNECTIONS");
		double linksShort = cells.stream().mapToInt(c -> connectionsShort.getConnections(c).size()).average().orElse(0);
		say("links per cell: " + linksAll + " without threshold, " + linksShort + " up to 7 µm");
		check("distance threshold drops links", linksShort < linksAll);
		runPlugin("Delaunay Connections (same class)", "qupath.ext.qust.DelaunayConnections", "{\"maxDistance\":0.0,\"sameClass\":true}", data);
		var connectionsSame = (qupath.lib.objects.PathObjectConnections) data.getProperty("OBJECT_CONNECTIONS");
		check("links join cells of one class", cells.stream().allMatch(c -> connectionsSame.getConnections(c).stream().allMatch(n -> n.getPathClass() == c.getPathClass())));
		data.removeProperty("OBJECT_CONNECTIONS");

		// Simple tissue detection on a separate slide: a dark rectangle with a hole, and a small second piece, on a white background
		BufferedImage tissueImg = new BufferedImage(1200, 1200, BufferedImage.TYPE_3BYTE_BGR);
		java.awt.Graphics2D tg = tissueImg.createGraphics();
		tg.setColor(java.awt.Color.WHITE);
		tg.fillRect(0, 0, 1200, 1200);
		tg.setColor(new java.awt.Color(180, 90, 150));
		tg.fillRect(300, 300, 600, 600);
		tg.fillRect(1000, 1000, 100, 100);
		tg.setColor(java.awt.Color.WHITE);
		tg.fillRect(500, 500, 100, 100);
		tg.dispose();
		WrappedBufferedImageServer tissueServer = new WrappedBufferedImageServer("tissue", tissueImg);
		tissueServer.setMetadata(new qupath.lib.images.servers.ImageServerMetadata.Builder(tissueServer.getMetadata()).pixelSizeMicrons(0.5, 0.5).build());
		String tissueParams = "{\"threshold\":200,\"requestedPixelSizeMicrons\":5.0,\"minAreaMicrons\":1000.0,\"maxHoleAreaMicrons\":1000.0,\"darkBackground\":false,"
				+ "\"smoothImage\":true,\"medianCleanup\":true,\"dilateBoundaries\":false,\"smoothCoordinates\":true,\"excludeOnBoundary\":false,\"singleAnnotation\":%s}";
		ImageData<BufferedImage> tissueData = new ImageData<>(tissueServer);
		runPlugin("Simple Tissue Detection", "qupath.ext.qust.PetesSimpleTissueDetection", String.format(tissueParams, "false"), tissueData);
		var tissue = new ArrayList<>(tissueData.getHierarchy().getAnnotationObjects());
		double tissueArea = tissue.stream().mapToDouble(a -> a.getROI().getArea()).sum();
		say("tissue annotations: " + tissue.size() + ", area " + tissueArea + " px^2 (expected about 360000: 600x600 minus a 100x100 hole plus a 100x100 piece); bounds "
				+ tissue.stream().map(a -> String.format("[%.0f..%.0f x %.0f..%.0f]", a.getROI().getBoundsX(), a.getROI().getBoundsX() + a.getROI().getBoundsWidth(),
						a.getROI().getBoundsY(), a.getROI().getBoundsY() + a.getROI().getBoundsHeight())).toList());
		check("one annotation per piece of tissue", tissue.size() == 2);
		// the thresholded image has a pixel of 10 px, and its edge pixels fall just inside the region: about a pixel is lost along the outline
		check("tissue area follows the pieces", Math.abs(tissueArea - 360000) < 43200);
		check("the hole of the tissue is kept", tissue.stream().anyMatch(a -> a.getROI().getGeometry().getGeometryN(0) instanceof org.locationtech.jts.geom.Polygon p && p.getNumInteriorRing() == 1));
		// the outline that the viewer draws is the outline of the region: no ring of the shape lies outside the geometry's boundary
		boolean outlineOk = true;
		for (var a : tissue) {
			var geometry = a.getROI().getGeometry();
			var boundary = geometry.getBoundary();
			var path = a.getROI().getShape().getPathIterator(null);
			double[] c = new double[6];
			double px = 0, py = 0, sx = 0, sy = 0;
			while (!path.isDone()) {
				int type = path.currentSegment(c);
				if (type == java.awt.geom.PathIterator.SEG_MOVETO) { px = c[0]; py = c[1]; sx = px; sy = py; }
				else if (type == java.awt.geom.PathIterator.SEG_LINETO) {
					var mid = geometry.getFactory().createPoint(new org.locationtech.jts.geom.Coordinate((px + c[0]) / 2, (py + c[1]) / 2));
					if (boundary.distance(mid) > 1.0) outlineOk = false;
					px = c[0]; py = c[1];
				} else if (type == java.awt.geom.PathIterator.SEG_CLOSE) {
					var mid = geometry.getFactory().createPoint(new org.locationtech.jts.geom.Coordinate((px + sx) / 2, (py + sy) / 2));
					if (boundary.distance(mid) > 1.0) outlineOk = false;
				}
				path.next();
			}
		}
		check("every segment of the drawn outline lies on the boundary of the region", outlineOk);
		ImageData<BufferedImage> tissueData1 = new ImageData<>(tissueServer);
		runPlugin("Simple Tissue Detection (single annotation)", "qupath.ext.qust.PetesSimpleTissueDetection", String.format(tissueParams, "true"), tissueData1);
		check("single annotation for all pieces", tissueData1.getHierarchy().getAnnotationObjects().size() == 1
				&& Math.abs(tissueData1.getHierarchy().getAnnotationObjects().iterator().next().getROI().getArea() - tissueArea) < 1000);

		// Delaunay neighbours of two pieces of tissue 200 px apart: no link crosses the gap, with and without the command
		BufferedImage twoImg = new BufferedImage(1000, 300, BufferedImage.TYPE_3BYTE_BGR);
		ImageData<BufferedImage> twoData = new ImageData<>(new WrappedBufferedImageServer("two", twoImg));
		for (int[] box : new int[][] { { 0, 0, 400, 300 }, { 600, 0, 400, 300 } })
			twoData.getHierarchy().addObject(PathObjects.createAnnotationObject(ROIs.createRectangleROI(box[0], box[1], box[2], box[3], null)));
		for (int offset : new int[] { 20, 620 })
			for (int i = 0; i < 12; i++)
				for (int j = 0; j < 12; j++)
					twoData.getHierarchy().addObject(PathObjects.createDetectionObject(ROIs.createEllipseROI(offset + i * 30 + (j % 2) * 15, 15 + j * 22, 8, 8, null)));
		Class<?> connectionsClass = Class.forName("qupath.ext.qust.CellConnections");
		java.lang.reflect.Method connectionsOf = connectionsClass.getDeclaredMethod("of", ImageData.class);
		java.lang.reflect.Method connectionsGet = connectionsClass.getDeclaredMethod("getConnections", PathObject.class);
		connectionsOf.setAccessible(true);
		connectionsGet.setAccessible(true);
		var twoCells = new ArrayList<>(twoData.getHierarchy().getDetectionObjects());
		Object fallback = connectionsOf.invoke(null, twoData);
		boolean crossesFallback = false;
		int linksFallback = 0;
		for (PathObject c : twoCells)
			for (Object n : (List<?>) connectionsGet.invoke(fallback, c)) {
				linksFallback++;
				if (((PathObject) n).getParent() != c.getParent())
					crossesFallback = true;
			}
		say("two pieces: " + twoCells.size() + " cells, " + linksFallback + " neighbour entries without the command");
		check("neighbours stay in their annotation (without the command)", !crossesFallback && linksFallback > 0);
		runPlugin("Delaunay Connections (two pieces)", "qupath.ext.qust.DelaunayConnections", "{\"maxDistance\":0.0,\"sameClass\":false}", twoData);
		Object stored = connectionsOf.invoke(null, twoData);
		boolean crossesStored = false;
		int linksStored = 0;
		for (PathObject c : twoCells)
			for (Object n : (List<?>) connectionsGet.invoke(stored, c)) {
				linksStored++;
				if (((PathObject) n).getParent() != c.getParent())
					crossesStored = true;
			}
		check("neighbours stay in their annotation (Delaunay Connections)", !crossesStored && linksStored == linksFallback);

		runPlugin("Pseudo Spot Generation", "qupath.ext.qust.PseudoVisiumSpotGeneration",
				"{\"spotDiameter\":20.0,\"minSpotDist\":40.0,\"rectGridArrangement\":false,\"rectShapeSpot\":false,\"prefix\":\"spot\"}", data);
		long spots = data.getHierarchy().getAnnotationObjects().stream().filter(a -> a.getPathClass() != null && a.getPathClass().toString().contains("spot")).count();
		say("pseudo spots: " + spots);
		check("pseudo spots generated", spots > 20);
		data.getHierarchy().removeObjects(data.getHierarchy().getAnnotationObjects().stream().filter(a -> a != annotation).toList(), false);

		File objDir = new File(out, "objcls");
		runPlugin("Export Images for Object Classification", "qupath.ext.qust.ObjectClassificationImageAcquisition",
				"{\"dist\":\"" + objDir + "\",\"normalization\":true,\"dontResampling\":true,\"samplingSize\":36,\"allSamples\":true}", data);
		String[] objFiles = objDir.list();
		say("object export: " + (objFiles == null ? 0 : objFiles.length) + " files");
		check("object classification images exported", objFiles != null && objFiles.length == 900);


		File regDir = new File(out, "regseg");
		runPlugin("Export Images for Region Segmentation", "qupath.ext.qust.RegionSegmentationImageAcquisition",
				"{\"dist\":\"" + regDir + "\",\"normalization\":true,\"dontResampling\":true,\"samplingSize\":64,\"samplingStride\":32,\"allSamples\":true}", data);
		String[] regFiles = regDir.list();
		say("region export: " + (regFiles == null ? 0 : regFiles.length) + " files");
		check("region segmentation images exported", regFiles != null && regFiles.length > 0);

		st2wsi(out);
		// last: it adds region annotations to the slide
		if (args.length > 3)
			pythonRoundTrip(args[2], args[3], out, objDir, data, cells);

		if (System.getProperty("qust.stardist.models") != null)
			starDist(System.getProperty("qust.stardist.models"), data);
		if (System.getProperty("qust.cellpose.python") != null)
			cellpose(System.getProperty("qust.cellpose.python"), data);

		say(failures == 0 ? "ALL PASSED" : failures + " FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}

	/**
	 * ST2WSI Registration on a synthetic pair: the DAPI image is the haematoxylin of the H&E image, turned 90 degrees left, so
	 * that the registration with a 90 degree rotation has to find the identity.
	 */
	static void st2wsi(String out) throws Exception {
		int w = 1400, h = 1000;
		BufferedImage he = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
		java.awt.Graphics2D g = he.createGraphics();
		g.setColor(new java.awt.Color(235, 200, 220));
		g.fillRect(0, 0, w, h);
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		Random rnd = new Random(5);
		for (int i = 0; i < 160; i++) {
			int r = 20 + rnd.nextInt(40);
			g.setColor(new java.awt.Color(90 + rnd.nextInt(50), 30 + rnd.nextInt(40), 120 + rnd.nextInt(50)));
			g.fillOval(rnd.nextInt(w - r), rnd.nextInt(h - r), r, r + rnd.nextInt(20));
		}
		g.dispose();
		BufferedImage dapi = new BufferedImage(h, w, BufferedImage.TYPE_BYTE_GRAY);
		for (int y = 0; y < h; y++)
			for (int x = 0; x < w; x++) {
				int rgb = he.getRGB(x, y);
				double luminance = 0.299 * ((rgb >> 16) & 255) + 0.587 * ((rgb >> 8) & 255) + 0.114 * (rgb & 255);
				int v = (int) Math.max(0, Math.min(255, 255 - luminance));
				dapi.getRaster().setSample(y, w - 1 - x, 0, v);
			}
		File dir = new File(out, "st2wsi");
		dir.mkdirs();
		File dapiFile = new File(dir, "dapi.png");
		javax.imageio.ImageIO.write(dapi, "png", dapiFile);

		WrappedBufferedImageServer server = new WrappedBufferedImageServer("he", he);
		ImageData<BufferedImage> data = new ImageData<>(server);
		runPlugin("ST2WSI Registration", "qupath.ext.qust.ST2WSIRegistration",
				"{\"xeniumDir\":\"" + dir + "\",\"dapiImage\":\"" + dapiFile + "\",\"refSeries\":\"1\",\"tgtSeries\":\"1\","
						+ "\"refRotated\":\"90\",\"refFlipped\":false,\"pxlSz\":0.2125}", data);
		File json = new File(dir, "registration_params.json");
		check("ST2WSI wrote registration_params.json, direct_transf.txt and the overlays", json.exists() && new File(dir, "direct_transf.txt").exists()
				&& new File(dir, "alignment_sift_overlay.png").exists() && new File(dir, "alignment_bunwarpj_overlay.png").exists());
		if (!json.exists())
			return;
		org.json.JSONObject o = new org.json.JSONObject(java.nio.file.Files.readString(json.toPath()));
		org.json.JSONArray m = o.getJSONArray("xnumAnnotImgRegParamSiftMatrix");
		say("ST2WSI affine: " + m + ", source size " + o.getInt("xnumAnnotImgRegParamSrcImgWidth") + " x " + o.getInt("xnumAnnotImgRegParamSrcImgHeight"));
		check("ST2WSI finds the identity", Math.abs(m.getDouble(0) - 1) < 0.03 && Math.abs(m.getDouble(4) - 1) < 0.03 && Math.abs(m.getDouble(1)) < 0.03
				&& Math.abs(m.getDouble(3)) < 0.03 && Math.abs(m.getDouble(2)) < 5 && Math.abs(m.getDouble(5)) < 5);
		check("ST2WSI records the size of the DAPI image and the scales", o.getInt("xnumAnnotImgRegParamSrcImgWidth") == h
				&& o.getInt("xnumAnnotImgRegParamSrcImgHeight") == w && o.getInt("xnumAnnotImgRegParamSourceScale") == 1
				&& o.getInt("xnumAnnotImgRegParamTargetScale") == 1 && o.getString("xnumAnnotImgRegParamRotation").equals("90"));
	}

	/** The path preferences of QuST are folder or file preferences of the preference pane (they come with a browse button). */
	static void pathPreferences(QuPathGUI gui) throws Exception {
		String[][] expected = { { "QuST script directory", "DIRECTORY" }, { "StarDist model directory", "DIRECTORY" },
				{ "CCI dataset file", "FILE" }, { "Object Classification model directory", "DIRECTORY" },
				{ "Region Segmentation model directory", "DIRECTORY" }, { "QuST environment name or directory", "DIRECTORY" } };
		var items = fx(() -> new ArrayList<>(gui.getPreferencePane().getPropertySheet().getItems()));
		for (String[] e : expected) {
			var item = items.stream().filter(i -> e[0].equals(i.getName())).findFirst().orElse(null);
			String editor = item == null ? "missing" : item.getClass().getSimpleName() + " " + item.getType().getSimpleName() + " " + item.getPropertyEditorClass().map(Class::getSimpleName).orElse("-");
			say("Preference " + e[0] + ": " + editor);
			check("Preference '" + e[0] + "' has a " + e[1].toLowerCase() + " chooser", item != null && editor.toUpperCase().startsWith(e[1]));
		}
	}

	/** The parameter dialogs of the commands that ask for a path get a Browse... button next to the text field. */
	static void browseButtons(QuPathGUI gui, String root) throws Exception {
		String[][] cases = {
				{ "Preparation...", "ST2WSI Registration", "2" },
				{ "Import...", "10x Xenium Annotation", "1" },
				{ "Import...", "NanoString CosMx Annotation", "1" },
				{ "Import...", "10x Visium Annotation", "1" },
				{ "Import...", "10x Xenium Image Registration Parameters", "1" },
				{ "Classification and Segmentation...", "Export Images for Object Classification", "1" } };
		for (String[] c : cases) {
			List<Window> before = fx(() -> new ArrayList<>(Window.getWindows()));
			fx(() -> {
				gui.getMenu(root + ">" + c[0], false).getItems().stream().filter(i -> c[1].equals(i.getText())).findFirst().get().fire();
				return null;
			});
			int expected = Integer.parseInt(c[2]);
			int found = 0;
			for (int i = 0; i < 40 && found < expected; i++) {
				Thread.sleep(250);
				found = fx(() -> {
					int n = 0;
					for (Window w : Window.getWindows())
						if (!before.contains(w) && w.getScene() != null)
							for (Node b : w.getScene().getRoot().lookupAll(".button"))
								if (b instanceof Button button && "Browse...".equals(button.getText()))
									n++;
					return n;
				});
			}
			say("Browse buttons in the dialog of " + c[1] + ": " + found);
			check("Browse... buttons in the dialog of " + c[1], found == expected);
			fx(() -> {
				for (Window w : Window.getWindows())
					if (!before.contains(w) && w.getScene() != null) {
						try {
							javafx.scene.image.WritableImage image = w.getScene().getRoot().snapshot(null, null);
							javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(image, null), "png",
									new File(System.getProperty("java.io.tmpdir"), "qust-dialog-" + c[1].replaceAll("[^A-Za-z0-9]", "_") + ".png"));
						} catch (Exception e) {
							say("could not save a snapshot of the dialog: " + e);
						}
					}
				return null;
			});
			fx(() -> {
				for (Window w : new ArrayList<>(Window.getWindows()))
					if (!before.contains(w))
						w.hide();
				return null;
			});
			Thread.sleep(300);
		}
	}

	/** Trains a small model on the exported patches with python/train.py, then applies it with the Object Classification command. */
	static void pythonRoundTrip(String python, String scriptDir, String out, File imageDir, ImageData<BufferedImage> data,
			List<PathObject> cells) throws Exception {
		File table = new File(out, "objcls.txt");
		StringBuilder sb = new StringBuilder("Object ID\tClassification\n");
		for (PathObject c : cells)
			sb.append(c.getID()).append('\t').append(c.getPathClass()).append('\n');
		java.nio.file.Files.writeString(table.toPath(), sb.toString());

		File models = new File(out, "models");
		Process p = new ProcessBuilder(python, scriptDir + "/train.py", "smoke", "-i", imageDir.toString(), "-d", table.toString(),
				"-o", models.toString(), "-p", "0.2125", "-m", "resnet18", "-bs", "64", "-ne", "2", "--num_workers", "0")
						.redirectErrorStream(true).start();
		String log = new String(p.getInputStream().readAllBytes());
		int code = p.waitFor();
		say("train.py exit code " + code);
		if (code != 0)
			say(log.substring(Math.max(0, log.length() - 1500)));
		String[] modelFiles = models.list();
		say("models: " + (modelFiles == null ? "none" : String.join(", ", modelFiles)));
		check("model trained", code == 0 && modelFiles != null && new File(models, "smoke.final.pt").exists());
		if (code != 0)
			return;

		qupath.ext.qust.QuSTSetup setup = qupath.ext.qust.QuSTSetup.getInstance();
		setup.setEnvironmentType(qupath.ext.qust.VirtualEnvironmentRunner.EnvType.EXE);
		setup.setEnvironmentNameOrPath(python);
		setup.setScriptPath(scriptDir);
		setup.setObjclsModelLocationPath(models.toString());
		setup.setImageFileFormat("png");
		for (PathObject c : cells)
			c.setPathClass(null);
		runPlugin("Object Classification", "qupath.ext.qust.ObjectClassification",
				"{\"modelName\":\"smoke.final\",\"includeProbability\":true,\"batchSize\":128,\"maxThread\":1}", data);
		Thread.sleep(3000);
		long classified = cells.stream().filter(c -> c.getPathClass() != null && c.getPathClass().toString().contains("smoke.final")).count();
		say("cells classified by the trained model: " + classified + " (e.g. " + cells.get(0).getPathClass() + ")");
		check("object classification applied", classified == cells.size());

		runPlugin("DBSCAN-CellX", "qupath.ext.qust.DBSCANCellX",
				"{\"edgeMode\":true,\"angelParam\":140,\"keepUncorr\":true,\"samplingNum\":0,\"label\":\"smokedb\"}", data);
		long dbscan = cells.stream().filter(c -> c.getMeasurementList().getMeasurementNames().stream().anyMatch(n -> n.toLowerCase().contains("smokedb"))).count();
		say("cells with DBSCAN-CellX measurements: " + dbscan);
		check("DBSCAN-CellX measurements written", dbscan > 0);

		setup.setRegsegModelLocationPath(models.toString());
		int before = data.getHierarchy().getAnnotationObjects().size();
		runPlugin("Region Segmentation", "qupath.ext.qust.RegionSegmentation",
				"{\"modelName\":\"smoke.final\",\"detection_resolution\":10.0,\"detection\":false,\"smooth_coeff\":1.0,\"batchSize\":128,\"maxThread\":1}", data);
		Thread.sleep(3000);
		int after = data.getHierarchy().getAnnotationObjects().size();
		say("annotations before/after region segmentation: " + before + " / " + after);
		check("region segmentation created annotations", after > before);
	}

	/** Runs StarDist nucleus detection (needs the QuPath StarDist extension on the class path and a .pb model). */
	static void starDist(String modelDir, ImageData<BufferedImage> data) throws Exception {
		qupath.ext.qust.QuSTSetup.getInstance().setStardistModelLocationPath(modelDir);
		PathObject area = PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 600, 600, null));
		data.getHierarchy().addObject(area);
		long before = data.getHierarchy().getDetectionObjects().size();
		data.getHierarchy().getSelectionModel().setSelectedObject(area);
		// the synthetic slide is RGB, so take an H&E model (the dsb2018 ones expect a single fluorescence channel)
		String model = Arrays.stream(new File(modelDir).list((d, n) -> n.endsWith(".pb"))).sorted().filter(n -> n.startsWith("he_")).findFirst().orElseThrow();
		QP.runPlugin("qupath.ext.qust.StarDistCellNucleusDetection", data, "{\"starDistModel\":\"" + model + "\",\"threshold\":0.5}");
		Thread.sleep(5000);
		long after = data.getHierarchy().getDetectionObjects().size();
		say("StarDist model " + model + ": detections before/after " + before + " / " + after);
		// the synthetic slide has no real nuclei: this only shows that OpenCV and the model load and run (errors show up in the log)
		check("StarDist ran", after >= before);
	}

	/**
	 * Runs Cellpose nucleus detection (needs the BIOP Cellpose extension on the class path and a Python with Cellpose).
	 * The extension keeps its temporary files in the project folder and Cellpose downloads its model weights on first
	 * use, so a real detection needs a project and the weights; this only shows that the command finds the extension
	 * and the setup, and refuses to run on a slide that is not in a project.
	 */
	static void cellpose(String python, ImageData<BufferedImage> data) throws Exception {
		Class<?> setupClass = Class.forName("qupath.ext.biop.cellpose.CellposeSetup");
		Object setup = setupClass.getMethod("getInstance").invoke(null);
		setupClass.getMethod("setCellposePythonPath", String.class).invoke(setup, python);
		try { // only newer versions of the extension have a separate Cellpose-SAM environment
			setupClass.getMethod("setCellposeSAMPythonPath", String.class).invoke(setup, python);
		} catch (NoSuchMethodException e) {
			// not available in this version
		}
		PathObject area = PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 600, 600, null));
		data.getHierarchy().addObject(area);
		long before = data.getHierarchy().getDetectionObjects().size();
		data.getHierarchy().getSelectionModel().setSelectedObject(area);
		say("run Cellpose Nucleus Detection (the slide is not in a project: it must stop with a message, see the log)");
		QP.runPlugin("qupath.ext.qust.CellposeCellNucleusDetection", data, "{\"cellposeModel\":\"cyto3\",\"useGPU\":false}");
		Thread.sleep(2000);
		long after = data.getHierarchy().getDetectionObjects().size();
		say("Cellpose detections before/after " + before + " / " + after);
		check("Cellpose command stopped cleanly without a project", after == before);
	}

	static void runPlugin(String name, String cls, String json, ImageData<BufferedImage> data) {
		try {
			say("run " + name);
			// several commands clear the selection when they finish
			var annotations = data.getHierarchy().getAnnotationObjects();
			if (!annotations.isEmpty())
				data.getHierarchy().getSelectionModel().setSelectedObject(annotations.iterator().next());
			QP.runPlugin(cls, data, json);
			Thread.sleep(1500);
		} catch (Throwable e) {
			say("FAIL " + name + ": " + e);
			e.printStackTrace(System.out);
			failures++;
		}
	}
}
