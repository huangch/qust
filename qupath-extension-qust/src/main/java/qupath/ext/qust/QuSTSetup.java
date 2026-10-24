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

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.StringProperty;
import qupath.ext.qust.VirtualEnvironmentRunner.EnvType;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * Shared, preference-backed settings of the QuST extension.
 */
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

	private static final QuSTSetup instance = new QuSTSetup();

	public QuSTSetup() {
		StringProperty qustScriptPathProp = PathPrefs.createPersistentPreference("qustScriptPath", "");
		qustScriptPath = qustScriptPathProp.get();

		StringProperty stardistModelLocationPathProp = PathPrefs.createPersistentPreference("stardistModelLocationPath", "");
		stardistModelLocationPath = stardistModelLocationPathProp.get();

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

	public static QuSTSetup getInstance() {
		return instance;
	}

	public EnvType getEnvironmentType() {
		return qustEnvType;
	}

	public void setEnvironmentType(EnvType qustEnvType) {
		this.qustEnvType = qustEnvType;
	}

	public String getEnvironmentNameOrPath() {
		return qustEnvNameOrPath;
	}

	public void setEnvironmentNameOrPath(String qustEnvNameOrPath) {
		this.qustEnvNameOrPath = qustEnvNameOrPath;
	}

	public String getScriptPath() {
		return qustScriptPath;
	}

	public void setScriptPath(String qustScriptPath) {
		this.qustScriptPath = qustScriptPath;
	}

	public String getStardistModelLocationPath() {
		return stardistModelLocationPath;
	}

	public void setStardistModelLocationPath(String stardistModelLocationPath) {
		this.stardistModelLocationPath = stardistModelLocationPath;
	}

	public String getCciDatasetLocationPath() {
		return cciDatasetLocationPath;
	}

	public void setCciDatasetLocationPath(String cciDatasetLocationPath) {
		this.cciDatasetLocationPath = cciDatasetLocationPath;
	}

	public String getObjclsModelLocationPath() {
		return objclsModelLocationPath;
	}

	public void setObjclsModelLocationPath(String objclsModelLocationPath) {
		this.objclsModelLocationPath = objclsModelLocationPath;
	}

	public String getRegsegModelLocationPath() {
		return regsegModelLocationPath;
	}

	public void setRegsegModelLocationPath(String regsegModelLocationPath) {
		this.regsegModelLocationPath = regsegModelLocationPath;
	}

	public String getImageFileFormat() {
		return imageFileFormat;
	}

	public void setImageFileFormat(String imageFileFormat) {
		this.imageFileFormat = imageFileFormat;
	}

	public int getNormalizationSampleSize() {
		return normalizationSampleSize;
	}

	public void setNormalizationSampleSize(int normalizationSampleSize) {
		this.normalizationSampleSize = normalizationSampleSize;
	}
}
