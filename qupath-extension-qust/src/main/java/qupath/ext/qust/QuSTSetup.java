package qupath.ext.qust;

//import java.nio.file.Paths;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.property.IntegerProperty;
import qupath.ext.qust.VirtualEnvironmentRunner.EnvType;
import qupath.lib.gui.prefs.PathPrefs;

public class QuSTSetup {
	private EnvType QuSTEnvType;
    private String QuSTEnvNameOrPath;
    private String QuSTScriptPath;
    private String stardistModelLocationPath;
    private String cciDatasetLocationPath;
    private String objclsModelLocationPath;
    private String regsegModelLocationPath;
    private String imageFileFormat;
    private int normalizationSampleSize;
    
    private static QuSTSetup instance = new QuSTSetup();

    public QuSTSetup() {
    	final StringProperty stardistModelLocationPathProp = PathPrefs.createPersistentPreference("stardistModelLocationPath", "");
    	stardistModelLocationPath = stardistModelLocationPathProp.get();
    	
    	final StringProperty cciDatasetLocationPathProp = PathPrefs.createPersistentPreference("cciDatasetLocationPath", "");
    	cciDatasetLocationPath = cciDatasetLocationPathProp.get();
    	
    	final StringProperty objclsModelLocationPathProp = PathPrefs.createPersistentPreference("objclsModelLocationPath", "");
    	objclsModelLocationPath = objclsModelLocationPathProp.get();
    	
    	final StringProperty regsegModelLocationPathProp = PathPrefs.createPersistentPreference("regsegModelLocationPath", "");
    	regsegModelLocationPath = regsegModelLocationPathProp.get();    	
    	
    	final StringProperty imageFileFormatProp = PathPrefs.createPersistentPreference("imageFileFormat", "");
    	imageFileFormat = imageFileFormatProp.get();    	

    	final IntegerProperty normalizationSampleSizeProp = PathPrefs.createPersistentPreference("normalizationSampleSize", 1000);
    	normalizationSampleSize = normalizationSampleSizeProp.get();
    	
    	// Create the options we need
        ObjectProperty<EnvType> QuSTEnvTypeProp = PathPrefs.createPersistentPreference("QuSTEnvType", EnvType.EXE, EnvType.class);
        StringProperty envPathProp = PathPrefs.createPersistentPreference("QuSTEnvPath", "");

        //Set options to current values
        QuSTEnvType = QuSTEnvTypeProp.get();
        QuSTEnvNameOrPath = envPathProp.get();
    }
    
    
    public EnvType getEnvironmentType() {
        return QuSTEnvType;
    }

    
    public void setEnvironmentType(EnvType QuSTEnvType) {
        this.QuSTEnvType = QuSTEnvType;
    }

    
    public String getEnvironmentNameOrPath() {
        return QuSTEnvNameOrPath;
    }


    public void setEnvironmentNameOrPath(String QuSTEnvNameOrPath) {
        this.QuSTEnvNameOrPath = QuSTEnvNameOrPath;
    }

    
    public static QuSTSetup getInstance() {
        return instance;
    }

    
    public String getStardistModelLocationPath() {
        return stardistModelLocationPath;
    }

    
    public void setStardistModelLocationPath(String stardistModelLocationPath) {
        this.stardistModelLocationPath = stardistModelLocationPath;
    }

    
    public String getSptx2ScriptPath() {
        return QuSTScriptPath;
    }

    
    public void setSptx2ScriptPath(String QuSTScriptPath) {
        this.QuSTScriptPath = QuSTScriptPath;
    }
    
    
    public String getCciDatasetLocationPath() {
        return cciDatasetLocationPath;
    }

    
    public void setCciDatasetLocationPath(String cciDatasetLocationPath) {
        this.cciDatasetLocationPath = cciDatasetLocationPath;
    }    
    
    
    public String getObjclsModelLocationPath() {
        return this.objclsModelLocationPath;
    }

    
    public void setObjclsModelLocationPath(String objclsModelLocationPath) {
        this.objclsModelLocationPath = objclsModelLocationPath;
    }
    

    public String getRegsegModelLocationPath() {
        return this.regsegModelLocationPath;
    }

    
    public void setRegsegModelLocationPath(String regsegModelLocationPath) {
        this.regsegModelLocationPath = regsegModelLocationPath;
    }    
    
    
    public String getImageFileFormat() {
        return this.imageFileFormat;
    }

    
    public void setImageFileFormat(String imageFileFormat) {
        this.imageFileFormat = imageFileFormat;
    }  
    
    public int getNormalizationSampleSize() {
        return this.normalizationSampleSize;
    }
    
    public void setNormalizationSampleSize(int normalizationSampleSize) {
        this.normalizationSampleSize = normalizationSampleSize;
    } 
}