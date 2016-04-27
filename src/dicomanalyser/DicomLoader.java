/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package dicomanalyser;

import javax.swing.JFileChooser;
import javax.swing.JFrame;

/**
 *
 * @author pc
 * Class for loading DICOM files from Database, Cloud, Folder, History or for single file
 */
public class DicomLoader {
    
    
    public String[] getFolder(JFrame frame) {
        String[] dcmFiles = null;
        JFileChooser chooser = new JFileChooser();
        try {
            chooser.setCurrentDirectory(new java.io.File("."));
            chooser.setDialogTitle("");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);
            if(chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {                 
                dcmFiles = chooser.getSelectedFile().list();                               
                for(int i = 0; i < dcmFiles.length; i++) {
                    dcmFiles[i] = chooser.getSelectedFile() + "/" + dcmFiles[i]; //make valid path            
                }                   
            }                                   
            else {
                System.out.println("No Selection ");
            }
        } catch(Exception e) {System.err.println("DicomLoader.getFolder error: " + e);}
        
        return dcmFiles;
    }   
    
    
    public void getHistory() {
        
    }
    
    
    public void getCloud() {
        
    }
    
    
    public void getDatabase() {
        
    }
}