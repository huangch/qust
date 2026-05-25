package qupath.ext.qust;

//import java.nio.file.Paths;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.property.IntegerProperty;
import qupath.ext.qust.VirtualEnvironmentRunner.EnvType;
import qupath.lib.gui.prefs.PathPrefs;

public class QuSTSetup {
	private EnvType qustEnvType;
    private String qustEnvNameOrPath;
    private String qustScriptPath;
    private String stardistModelLocationPath;
    private String cciDatasetLocationPath;
    private String objclsModelLocationPath;
    private String regsegModelLocationPath;
    private String imageFileFormat;
    private int normalizationSampleSize;
    
    private static QuSTSetup instance = new QuSTSetup();

    public QuSTSetup() {
    	StringProperty stardistModelLocationPathProp = PathPrefs.createPersistentPreference("stardistModelLocationPath", "");
    	stardistModelLocationPath = stardistModelLocationPathProp.get();
    	
    	StringProperty qustScriptPathProp = PathPrefs.createPersistentPreference("qustScriptPath", "");
    	qustScriptPath = qustScriptPathProp.get();
    	
    	StringProperty cciDatasetLocationPathProp = PathPrefs.createPersistentPreference("cciDatasetLocationPath", "");
    	cciDatasetLocationPath = cciDatasetLocationPathProp.get();
    	
    	StringProperty objclsModelLocationPathProp = PathPrefs.createPersistentPreference("objclsModelLocationPath", "");
    	objclsModelLocationPath = objclsModelLocationPathProp.get();
    	
    	StringProperty regsegModelLocationPathProp = PathPrefs.createPersistentPreference("regsegModelLocationPath", "");
    	regsegModelLocationPath = regsegModelLocationPathProp.get();    	
    	
    	StringProperty imageFileFormatProp = PathPrefs.createPersistentPreference("imageFileFormat", "png");
    	imageFileFormat = imageFileFormatProp.get();    	

    	IntegerProperty normalizationSampleSizeProp = PathPrefs.createPersistentPreference("normalizationSampleSize", 100);
    	normalizationSampleSize = normalizationSampleSizeProp.get();
    	
        ObjectProperty<EnvType> qustEnvTypeProp = PathPrefs.createPersistentPreference("qustEnvType", EnvType.EXE, EnvType.class);
        qustEnvType = qustEnvTypeProp.get();
        
        StringProperty envPathProp = PathPrefs.createPersistentPreference("qustEnvPath", "");
        qustEnvNameOrPath = envPathProp.get();
    }
    
    
    public EnvType getEnvironmentType() {
        return this.qustEnvType;
    }
    
    public void setEnvironmentType(EnvType qustEnvType) {
        this.qustEnvType = qustEnvType;
    }

    
    public String getEnvironmentNameOrPath() {
        return this.qustEnvNameOrPath;
    }

    public void setEnvironmentNameOrPath(String qustEnvNameOrPath) {
        this.qustEnvNameOrPath = qustEnvNameOrPath;
    }

    
    public static QuSTSetup getInstance() {
        return instance;
    }

    
    public String getStardistModelLocationPath() {
        return this.stardistModelLocationPath;
    }

    
    public void setStardistModelLocationPath(String stardistModelLocationPath) {
        this.stardistModelLocationPath = stardistModelLocationPath;
    }

    
    public String getScriptPath() {
        return this.qustScriptPath;
    }

    
    public void setScriptPath(String QuSTScriptPath) {
        this.qustScriptPath = QuSTScriptPath;
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