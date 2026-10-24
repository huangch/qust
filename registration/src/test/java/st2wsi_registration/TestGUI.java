/*-
 * #%L
 * bUnwarpJ plugin for Fiji.
 * %%
 * Copyright (C) 2005 - 2020 Fiji developers.
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
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package st2wsi_registration;


import ij.IJ;
import ij.ImageJ;
import st2wsi_registration.ST2WSI_Registration;

/**
 * Class to test the plugin GUI using two grayscale images.
 *
 */
public class TestGUI {

	/**
	 * Main method to test and debug the bUnwarpJ GUI
	 *  
	 * @param args
	 */
	public static void main( final String[] args )
	{		
		ImageJ.main( args );

		IJ.open( TestGUI.class.getResource( "/files/bridge.png" ).getFile() );
		IJ.open( TestGUI.class.getResource( "/files/deformed-bridge.png" ).getFile() );

		new ST2WSI_Registration().run( null );
	}
}
