/*-
 * #%L
 * ST-AnD is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * ST-AnD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with ST-AnD.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.ext.qust;

import qupath.ext.qust.VirtualEnvironmentRunner.EnvType;


import javafx.beans.property.ObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.property.IntegerProperty;
import javafx.collections.FXCollections;
import javafx.scene.control.Menu;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.panes.PreferencePane;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.tools.MenuTools;
/**
 * Install QuST as an extension.
 * 
 * @author Chao Hui Huang
 */
public class QuSTExtension implements QuPathExtension, GitHubProject {
	
//	@SuppressWarnings("unchecked")
	@Override
	public void installExtension(QuPathGUI qupath) {
		QuSTSetup QuSTOptions = QuSTSetup.getInstance();
		
		// Create stardistModel Property Instance
        StringProperty stardistModelLocationPathProp = PathPrefs.createPersistentPreference("stardistModelLocationPath", "");
        QuSTOptions.setStardistModelLocationPath(stardistModelLocationPathProp.get());
        stardistModelLocationPathProp.addListener((v,o,n) -> QuSTOptions.setStardistModelLocationPath(n));
        
        // Add stardistModel Property to Preference Page
        PreferencePane stardistPrefs = QuPathGUI.getInstance().getPreferencePane();
        stardistPrefs.addPropertyPreference(stardistModelLocationPathProp, String.class, "Stardist model directory", "QuST",
                "Enter the directory where the stardist models are located.");
                
		// Create stardistModel Property Instance
        StringProperty QuSTScriptPathProp = PathPrefs.createPersistentPreference("qustScriptPath", "");
        QuSTOptions.setSptx2ScriptPath(QuSTScriptPathProp.get());
        QuSTScriptPathProp.addListener((v,o,n) -> QuSTOptions.setSptx2ScriptPath(n));
        
        // Add stardistModel Property to Preference Page
        PreferencePane QuSTScriptPathPrefs = QuPathGUI.getInstance().getPreferencePane();
        QuSTScriptPathPrefs.addPropertyPreference(QuSTScriptPathProp, String.class, "QuST directory", "QuST",
                "Enter the directory where the QuST scripts are located.");

		// Create cciDataset Property Instance
        StringProperty cciDatasetLocationPathProp = PathPrefs.createPersistentPreference("cciDatasetLocationPath", "");
        QuSTOptions.setCciDatasetLocationPath(cciDatasetLocationPathProp.get());
        cciDatasetLocationPathProp.addListener((v,o,n) -> QuSTOptions.setCciDatasetLocationPath(n));
        
        // Add cciDataset Property to Preference Page
        PreferencePane cciDatasetPrefs = QuPathGUI.getInstance().getPreferencePane();
        cciDatasetPrefs.addPropertyPreference(cciDatasetLocationPathProp, String.class, "CCI dataset file", "QuST",
                "Enter the CCI dataset file.");
        
		// Create Property Instance
        StringProperty objclsModelLocationPathProp = PathPrefs.createPersistentPreference("objclsModelLocationPath", "");
        QuSTOptions.setObjclsModelLocationPath(objclsModelLocationPathProp.get());
        objclsModelLocationPathProp.addListener((v,o,n) -> QuSTOptions.setObjclsModelLocationPath(n));
        
        // Add Property to Preference Page
        PreferencePane objclsPrefs = QuPathGUI.getInstance().getPreferencePane();
        objclsPrefs.addPropertyPreference(objclsModelLocationPathProp, String.class, "Object Classification model directory", "QuST",
                "Enter the directory where the object classification models are located.");        
        
		// Create Property Instance
        StringProperty regsegModelLocationPathProp = PathPrefs.createPersistentPreference("regsegModelLocationPath", "");
        QuSTOptions.setRegsegModelLocationPath(regsegModelLocationPathProp.get());
        regsegModelLocationPathProp.addListener((v,o,n) -> QuSTOptions.setRegsegModelLocationPath(n));
        
        // Add Property to Preference Page
        PreferencePane regsegPrefs = QuPathGUI.getInstance().getPreferencePane();
        regsegPrefs.addPropertyPreference(regsegModelLocationPathProp, String.class, "Region Segmentation model directory", "QuST",
                "Enter the directory where the region segmentation models are located.");        
        
        
        
        
        
        // Create Property Instance
        StringProperty imageFileFormatProp = PathPrefs.createPersistentPreference("imageFileFormat", "png");
        QuSTOptions.setImageFileFormat(imageFileFormatProp.get());
        imageFileFormatProp.addListener((v,o,n) -> QuSTOptions.setImageFileFormat(n));
        
        // Add Property to Preference Page
        PreferencePane imageFileFormatPrefs = QuPathGUI.getInstance().getPreferencePane();
        imageFileFormatPrefs.addPropertyPreference(imageFileFormatProp, String.class, "Default image file format", "QuST",
                "Enter the default image format, e.g., png, etc.");        
        
        
        

        
        // Create Property Instance
        IntegerProperty normalizationSampleSizeProp = PathPrefs.createPersistentPreference("normalizationSampleSize", 1000);
        QuSTOptions.setNormalizationSampleSize(normalizationSampleSizeProp.get());
        normalizationSampleSizeProp.addListener((v,o,n) -> QuSTOptions.setNormalizationSampleSize((int) n));
        
        // Add Property to Preference Page
        PreferencePane normalizationSampleSizePrefs = QuPathGUI.getInstance().getPreferencePane();
        normalizationSampleSizePrefs.addPropertyPreference(normalizationSampleSizeProp, Integer.class, "Default sample size for H&E staining normalizarion", "QuST",
                "Enter the default sample size for H&E staining normalizarion.");
        
        
        
        
        
        // Create the options we need
        ObjectProperty<EnvType> envType = PathPrefs.createPersistentPreference("qustEnvType", EnvType.EXE, EnvType.class);
        StringProperty envPath = PathPrefs.createPersistentPreference("qustEnvPath", "");

        //Set options to current values
        QuSTOptions.setEnvironmentType(envType.get());
        QuSTOptions.setEnvironmentNameOrPath(envPath.get());

        // Listen for property changes
        envType.addListener((v,o,n) -> QuSTOptions.setEnvironmentType(n));
        envPath.addListener((v,o,n) -> QuSTOptions.setEnvironmentNameOrPath(n));

        // Add Permanent Preferences and Populate Preferences
        PreferencePane prefs = QuPathGUI.getInstance().getPreferencePane();

        prefs.addPropertyPreference(envPath, String.class, "QuST Environment name or directory", "QuST",
                "Enter either the directory where your chosen Cellpose virtual environment (conda or venv) is located. Or the name of the conda environment you created.");
        prefs.addChoicePropertyPreference(envType,
                FXCollections.observableArrayList(VirtualEnvironmentRunner.EnvType.values()),
                VirtualEnvironmentRunner.EnvType.class,"QuST Environment Type", "QuST",
                "This changes how the environment is started.");
        
        
        
        
        
        
//		Menu menu = qupath.getMenu("Extensions>QuST Analysis Toolbox", true);

//		Menu importMenu = MenuTools.addMenuItems(menu, "Import...");
		Menu importMenu = qupath.getMenu("Extensions>QuST Analysis Toolbox>Import...", true);
		
//		MenuTools.addMenuItems(
//				importMenu,
//				qupath.createPluginAction("ST Annotation", STAnnotation.class, null)
//				);
		
//		MenuTools.addMenuItems(
//				importMenu,
//				qupath.createPluginAction("10x Visium Annotation", VisiumAnnotation.class, null)
//				);
		
		MenuTools.addMenuItems(
				importMenu,
				qupath.createPluginAction("10x Visium Image Registration Parameters", VisiumAnnotationImageRegistrationParameters.class, null)
				);		
		MenuTools.addMenuItems(
				importMenu,
				qupath.createPluginAction("10x Visium Annotation", VisiumAnnotation.class, null)
				);

		MenuTools.addMenuItems(
				importMenu,
				qupath.createPluginAction("10x Xenium Image Registration Parameters", XeniumAnnotationImageRegistrationParameters.class, null)
				);
		
		MenuTools.addMenuItems(
				importMenu,
				qupath.createPluginAction("10x Xenium Annotation", XeniumAnnotation.class, null)
				);
		

		
		MenuTools.addMenuItems(
				importMenu,
				qupath.createPluginAction("NanoString CosMX Annotation", CosmxAnnotation.class, null)
				);
		
		MenuTools.addMenuItems(
				importMenu,
				qupath.createPluginAction("AI-DIA Annotation", AiDiaAnnotation.class, null)
				);
		
//		Menu parationMenu = MenuTools.addMenuItems(menu, "Preparation...");
		Menu parationMenu = qupath.getMenu("Extensions>QuST Analysis Toolbox>Preparation...", true);
		
		MenuTools.addMenuItems(
				parationMenu,
				null,
				qupath.createPluginAction("Pete's Simple Tissue Detection", PetesSimpleTissueDetection.class, null)
				);			
		
		MenuTools.addMenuItems(
				parationMenu,
				qupath.createPluginAction("StarDist-based Nucleus Detection", StarDistCellNucleusDetection.class, null)
				);		
		
		MenuTools.addMenuItems(
				parationMenu,
				qupath.createPluginAction("Pseudo Spot Generation", PseudoVisiumSpotGeneration.class, null)
				);
		
//		Menu analysisMenu = MenuTools.addMenuItems(menu, "Analysis...");
		Menu analysisMenu = qupath.getMenu("Extensions>QuST Analysis Toolbox>Analysis...", true);

		MenuTools.addMenuItems(
				analysisMenu,
				qupath.createPluginAction("Cell Spatial Profiling by Classification - Compute the distance to the edge of a user defined cluster by classification", CellSpatialProfilingByClassification.class, null)
				);

		MenuTools.addMenuItems(
				analysisMenu,
				qupath.createPluginAction("Cell Spatial Profiling by Measurement - Compute the distance to the edge of a user defined cluster by measurement", CellSpatialProfilingByMeasurement.class, null)
				);
		
		MenuTools.addMenuItems(
				analysisMenu,
				qupath.createPluginAction("Cell-Cell Interaction Analysis - CCI for single cell spatial transcriptomics", CellCellInteractionAnalysis.class, null)
				);
		
		MenuTools.addMenuItems(
				analysisMenu,
				qupath.createPluginAction("Neighboring Cell Type Composition - Analyzing the composition of the neighboring cells", NeighboringCellTypeComposition.class, null)
				);
		
		MenuTools.addMenuItems(
				analysisMenu,
				qupath.createPluginAction("Density-based Cell Function Enrichment Analysis - Neighboring cell function enrichment analysis based on KDE", DensityCellFunctionEnrichment.class, null)
				);
		
		MenuTools.addMenuItems(
				analysisMenu,
				qupath.createPluginAction("Neighborhood-based Cell Function Enrichment Analysis - Neighboring cell function enrichment analysis based on Delaunay triangulation", NeighborhoodCellFunctionEnrichment.class, null)
				);
		
		MenuTools.addMenuItems(
				analysisMenu,
				qupath.createPluginAction("DBSCAN-CellX", DBSCANCellX.class, null)
				);
		
//		Menu deeplearningMenu = MenuTools.addMenuItems(menu, "deeplearning...");
		Menu deeplearningMenu = qupath.getMenu("Extensions>QuST Analysis Toolbox>Classification and Segmentation...", true);

		MenuTools.addMenuItems(
				deeplearningMenu,
				qupath.createPluginAction("Export Images for Object Classification", ObjectClassificationImageAcquisition.class, null)
				);

		MenuTools.addMenuItems(
				deeplearningMenu,
				qupath.createPluginAction("Object Classification", ObjectClassification.class, null)
				);
		
//		MenuTools.addMenuItems(
//				analysisMenu,
//				qupath.createPluginAction("Object Classification by DJL - multi-threading", ObjectClassificationDJL_MT.class, null)
//				);
//
//		MenuTools.addMenuItems(
//				analysisMenu,
//				qupath.createPluginAction("Object Classification by DJL - producer-comsumer", ObjectClassificationDJL_PC.class, null)
//				);
		
		MenuTools.addMenuItems(
				deeplearningMenu,
				qupath.createPluginAction("Object Classification by DJL - producer-comsumer mGPU", ObjectClassificationDJL_PC_mGPU.class, null)
				);
		
		MenuTools.addMenuItems(
				deeplearningMenu,
				qupath.createPluginAction("Export Images for Region Segmentation", RegionSegmentationImageAcquisition.class, null)
				);
		
		MenuTools.addMenuItems(
				deeplearningMenu,
				qupath.createPluginAction("Region Segmentation", RegionSegmentation.class, null)
				);		
		

		
//		MenuTools.addMenuItems(
//				analysisMenu,
//				qupath.createPluginAction("Interpreting Spatial Data using LLM based on High Ranking Key Genes", QuSTLLMHKG.class, null)
//				);
		
		
//		MenuTools.addMenuItems(
//				analysisMenu,
//				qupath.createPluginAction("Interpreting Spatial Data using LLM based on Comparative Key Genes", QuSTLLMCKG.class, null)
//				);		
		
//		MenuTools.addMenuItems(
//				analysisMenu,
//				qupath.createPluginAction("Discovering Spatial Insights based on Human Languages using LLM", QuSTLLMREQ.class, null)
//				);	
		
//		Action ExportPathDetectionObjectToOMECSVCommandAction = qupath.createImageDataAction(imageData -> ExportPathDetectionObjectToOMECSVCommand.runOMEObjectExport(qupath, imageData));
//		ExportPathDetectionObjectToOMECSVCommandAction.setText("Export objects in OMERO format to file");
//		
//		MenuTools.addMenuItems(
//				analysisMenu, ExportPathDetectionObjectToOMECSVCommandAction
//				);

//		Menu exportMenu = MenuTools.addMenuItems(menu, "export...");
		Menu exportMenu = qupath.getMenu("Extensions>QuST Analysis Toolbox>Export...", true);

		MenuTools.addMenuItems(
				exportMenu,
				qupath.createPluginAction("Export objects to Gzipped OMERO CSV (.ogz) file", DetectionObjectToOMECSV.class, null)
				);
		
		MenuTools.addMenuItems(
				exportMenu,
				qupath.createPluginAction("Export detection object measurements to H5AD file", DetectionMeasurementToH5AD.class, null)
				);	

		
//		Action ExportPathDetectionObjectMeasurementsToH5ADCommandAction = qupath.createImageDataAction(imageData -> ExportDetectionMeasurementsToH5ADCommand.runDetectionMeasurementsExport(qupath, imageData));
//		ExportPathDetectionObjectMeasurementsToH5ADCommandAction.setText("Export detection object measurements to H5AD file");
//		
//		MenuTools.addMenuItems(
//				analysisMenu, ExportPathDetectionObjectMeasurementsToH5ADCommandAction
//				);
	}

	@Override
	public String getName() {
		return "QuST Extension";
	}

	@Override
	public String getDescription() {
		return "Run QuST Extension.\n"
				+ "See the extension repository for citation information.";
	}
	
	@Override
	public Version getQuPathVersion() {
		return Version.parse("0.5.1");
	}

	@Override
	public GitHubRepo getRepository() {
		return GitHubRepo.create(getName(), "qupath", "qupath-extension-qust");
	}

}
