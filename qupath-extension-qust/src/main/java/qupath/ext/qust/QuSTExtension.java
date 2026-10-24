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

import java.util.List;
import java.util.function.Consumer;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.scene.control.Menu;
import qupath.ext.qust.VirtualEnvironmentRunner.EnvType;
import qupath.lib.common.Version;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder.PropertyType;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.panes.PreferencePane;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.tools.MenuTools;
import qupath.lib.plugins.PathPlugin;

/**
 * Installs QuST: single-cell spatial transcriptomics (ST) and whole slide image (WSI) analysis.
 *
 * @author Chao Hui Huang
 */
public class QuSTExtension implements QuPathExtension, GitHubProject {

	private static final String MENU = "Extensions>QuST Analysis Toolbox>";

	@Override
	public void installExtension(QuPathGUI qupath) {
		QuSTSetup setup = QuSTSetup.getInstance();
		PreferencePane prefs = qupath.getPreferencePane();

		// A "Browse..." button next to the fields that ask for a folder or a file (the prompts of the parameters)
		BrowseButtons.install(
				List.of("Xenium directory", "Xenium output directory", "Cosmx directory", "Visium output directory", "Destination folder", "Output directory"),
				List.of("DAPI image file (empty: use the Xenium directory)", "Custom model (path; empty: use the model above)"));

		bindPath(PropertyType.DIRECTORY, setup::setScriptPath, "qustScriptPath", "", prefs, "QuST script directory",
				"Directory with the QuST Python scripts (the 'python' folder of the QuST repository).");
		bindPath(PropertyType.DIRECTORY, setup::setStardistModelLocationPath, "stardistModelLocationPath", "", prefs, "StarDist model directory",
				"Directory with the StarDist models (.pb) used by StarDist nucleus detection.");
		bindPath(PropertyType.FILE, setup::setCciDatasetLocationPath, "cciDatasetLocationPath", "", prefs, "CCI dataset file",
				"Ligand-receptor table used by the cell-cell interaction analysis (data/cci/human_lr_pair.csv).");
		bindPath(PropertyType.DIRECTORY, setup::setObjclsModelLocationPath, "objclsModelLocationPath", "", prefs, "Object Classification model directory",
				"Directory where the object classification models are located.");
		bindPath(PropertyType.DIRECTORY, setup::setRegsegModelLocationPath, "regsegModelLocationPath", "", prefs, "Region Segmentation model directory",
				"Directory where the region segmentation models are located.");
		bind(setup::setImageFileFormat, "imageFileFormat", "png", prefs, "Default image file format",
				"Default format of the exported training images, e.g., png.");

		IntegerProperty sampleSize = PathPrefs.createPersistentPreference("normalizationSampleSize", 100);
		setup.setNormalizationSampleSize(sampleSize.get());
		sampleSize.addListener((v, o, n) -> setup.setNormalizationSampleSize(n.intValue()));
		prefs.addPropertyPreference(sampleSize, Integer.class, "Default sample size for H&E stain normalization", "QuST",
				"Number of image samples used to estimate the H&E stain normalization.");

		ObjectProperty<EnvType> envType = PathPrefs.createPersistentPreference("qustEnvType", EnvType.EXE, EnvType.class);
		setup.setEnvironmentType(envType.get());
		envType.addListener((v, o, n) -> setup.setEnvironmentType(n));
		prefs.addChoicePropertyPreference(envType, FXCollections.observableArrayList(EnvType.values()), EnvType.class,
				"QuST environment type", "QuST", "How the Python environment of QuST is started.");

		bindPath(PropertyType.DIRECTORY, setup::setEnvironmentNameOrPath, "qustEnvPath", "", prefs, "QuST environment name or directory",
				"Directory of the Python virtual environment (conda or venv), or the name of the conda environment.");

		Menu importMenu = qupath.getMenu(MENU + "Import...", true);
		add(qupath, importMenu, "10x Visium Image Registration Parameters", VisiumAnnotationImageRegistrationParameters.class);
		add(qupath, importMenu, "10x Visium Annotation", VisiumAnnotation.class);
		add(qupath, importMenu, "10x Xenium Image Registration Parameters", XeniumAnnotationImageRegistrationParameters.class);
		add(qupath, importMenu, "10x Xenium Annotation", XeniumAnnotation.class);
		add(qupath, importMenu, "NanoString CosMx Annotation", CosmxAnnotation.class);

		Menu preparationMenu = qupath.getMenu(MENU + "Preparation...", true);
		add(qupath, preparationMenu, "Simple Tissue Detection", PetesSimpleTissueDetection.class);
		add(qupath, preparationMenu, "ST2WSI Registration", ST2WSIRegistration.class);
		add(qupath, preparationMenu, "Delaunay Connections", DelaunayConnections.class);
		add(qupath, preparationMenu, "Normalize Transcripts", TranscriptNormalization.class);
		add(qupath, preparationMenu, "StarDist Nucleus Detection", StarDistCellNucleusDetection.class);
		add(qupath, preparationMenu, "Cellpose Nucleus Detection", CellposeCellNucleusDetection.class);
		add(qupath, preparationMenu, "Pseudo Spot Generation", PseudoVisiumSpotGeneration.class);

		Menu analysisMenu = qupath.getMenu(MENU + "Analysis...", true);
		add(qupath, analysisMenu, "Cell Spatial Profiling by Classification", CellSpatialProfilingByClassification.class);
		add(qupath, analysisMenu, "Cell Spatial Profiling by Measurement", CellSpatialProfilingByMeasurement.class);
		add(qupath, analysisMenu, "Neighboring Cell Type Composition", NeighboringCellTypeComposition.class);
		add(qupath, analysisMenu, "Cell-Cell Interaction Analysis", CellCellInteractionAnalysis.class);
		add(qupath, analysisMenu, "Cell Spatial Density", CellSpatialDensity.class);
		add(qupath, analysisMenu, "DBSCAN-CellX", DBSCANCellX.class);

		Menu dlMenu = qupath.getMenu(MENU + "Classification and Segmentation...", true);
		add(qupath, dlMenu, "Export Images for Object Classification", ObjectClassificationImageAcquisition.class);
		add(qupath, dlMenu, "Object Classification", ObjectClassification.class);
		add(qupath, dlMenu, "Export Images for Region Segmentation", RegionSegmentationImageAcquisition.class);
		add(qupath, dlMenu, "Region Segmentation", RegionSegmentation.class);
	}

	private static void bind(Consumer<String> target, String key, String defaultValue, PreferencePane prefs,
			String title, String description) {
		StringProperty prop = PathPrefs.createPersistentPreference(key, defaultValue);
		target.accept(prop.get());
		prop.addListener((v, o, n) -> target.accept(n));
		prefs.addPropertyPreference(prop, String.class, title, "QuST", description);
	}

	/** A path preference that comes with a browse button (a folder or a file chooser) in the preference pane. */
	private static void bindPath(PropertyType type, Consumer<String> target, String key, String defaultValue, PreferencePane prefs,
			String title, String description) {
		StringProperty prop = PathPrefs.createPersistentPreference(key, defaultValue);
		target.accept(prop.get());
		prop.addListener((v, o, n) -> target.accept(n));
		prefs.getPropertySheet().getItems().add(new PropertyItemBuilder<>(prop, String.class)
				.name(title).category("QuST").description(description).propertyType(type).build());
	}

	private static void add(QuPathGUI qupath, Menu menu, String title, Class<? extends PathPlugin<?>> plugin) {
		MenuTools.addMenuItems(menu, qupath.createPluginAction(title, plugin, null));
	}

	@Override
	public String getName() {
		return "QuST Extension";
	}

	@Override
	public String getDescription() {
		return "Integrative whole slide image and spatial transcriptomics analysis at single-cell level.\n"
				+ "See the extension repository for citation information.";
	}

	@Override
	public Version getQuPathVersion() {
		return Version.parse("0.7.0");
	}

	@Override
	public GitHubRepo getRepository() {
		return GitHubRepo.create(getName(), "huangch", "qust");
	}
}
