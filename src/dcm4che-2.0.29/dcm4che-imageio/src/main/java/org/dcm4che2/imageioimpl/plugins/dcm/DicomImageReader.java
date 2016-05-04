/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at http://sourceforge.net/projects/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * Gunter Zeilinger, Huetteldorferstr. 24/10, 1150 Vienna/Austria/Europe.
 * Portions created by the Initial Developer are Copyright (C) 2002-2005
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See listed authors below.
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

package org.dcm4che2.imageioimpl.plugins.dcm;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BandedSampleModel;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferShort;
import java.awt.image.DataBufferUShort;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.Raster;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

import org.dcm4che2.data.CombineDicomObject;
import org.dcm4che2.data.DicomObject;
import org.dcm4che2.data.Tag;
import org.dcm4che2.data.TransferSyntax;
import org.dcm4che2.data.UID;
import org.dcm4che2.data.VR;
import org.dcm4che2.image.ByteLookupTable;
import org.dcm4che2.image.ColorModelFactory;
import org.dcm4che2.image.LookupTable;
import org.dcm4che2.image.OverlayUtils;
import org.dcm4che2.image.PartialComponentSampleModel;
import org.dcm4che2.image.VOIUtils;
import org.dcm4che2.imageio.ImageReaderFactory;
import org.dcm4che2.imageio.ItemParser;
import org.dcm4che2.imageio.plugins.dcm.DicomImageReadParam;
import org.dcm4che2.imageio.plugins.dcm.DicomStreamMetaData;
import org.dcm4che2.io.DicomInputHandler;
import org.dcm4che2.io.DicomInputStream;
import org.dcm4che2.io.StopTagInputHandler;
import org.dcm4che2.util.ByteUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.media.imageio.stream.RawImageInputStream;
import com.sun.media.imageio.stream.SegmentedImageInputStream;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 * @version $Revision$ $Date$
 * @since Sep 2, 2006
 */
public class DicomImageReader extends ImageReader {

    private static final Logger log = LoggerFactory
            .getLogger(DicomImageReader.class);

    private static final String J2KIMAGE_READER = "com.sun.media.imageioimpl.plugins.jpeg2000.J2KImageReader";
    
    private static final String J2KIMAGE_READ_PARAM_FQCN = "com.sun.media.imageio.plugins.jpeg2000.J2KImageReadParam";

    private static final int[] OFFSETS_0 = { 0 };

    private static final int[] OFFSETS_0_0_0 = { 0, 0, 0 };

    private static final int[] OFFSETS_0_1_2 = { 0, 1, 2 };
    
    private static final Class<?> J2KIMAGE_READ_PARAM_CLASS;
    
    private static final Method J2KIMAGE_READ_PARAM_SET_RESOLUTION;
    
    static {
        
        Class<?> j2kImageReadParamClazz = null;
        Method setResolution = null;
        
        try {
            j2kImageReadParamClazz = DicomImageReader.class.getClassLoader().loadClass(J2KIMAGE_READ_PARAM_FQCN);
            setResolution = j2kImageReadParamClazz.getMethod("setResolution", int.class);
        } catch(Throwable t) {
            j2kImageReadParamClazz = null;
            setResolution = null;
            
            log.debug("unable to resolve jai provided J2KImageReadParam setResolution method", t);
        }
        
        J2KIMAGE_READ_PARAM_CLASS = j2kImageReadParamClazz;
        J2KIMAGE_READ_PARAM_SET_RESOLUTION = setResolution;
    }

    protected ImageInputStream iis;

    private DicomInputStream dis;

    protected DicomObject ds;
   
    protected int width;

    protected int height;

    protected int frames;

    protected int allocated;

    protected int stored;

    private int dataType;

    private int samples;

    private boolean monochrome;

    private boolean paletteColor;

    private boolean banded;

    private boolean bigEndian;

    private boolean swapByteOrder;   

    private long pixelDataPos;

    private int pixelDataLen;

    protected boolean compressed;

    private boolean clampPixelValues;

    private DicomStreamMetaData streamMetaData;

    protected ImageReader reader;

    private ItemParser itemParser;

    private SegmentedImageInputStream siis;
    
    private String pmi;
    
    private Float autoWindowCenter;
    private Float autoWindowWidth;
    
    /** Used to indicate whether or not to skip large private dicom elements.  */
    private boolean skipLargePrivate = false;

    /**
     * Store the transfer syntax locally in case it gets modified to re-write
     * the image
     */
    protected String tsuid;

    protected DicomImageReader(ImageReaderSpi originatingProvider) {
        super(originatingProvider);
    }

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);

        resetLocal();

        if (input != null) {
            if (!(input instanceof ImageInputStream)) {
                throw new IllegalArgumentException(
                        "Input not an ImageInputStream!");
            }
            this.iis = (ImageInputStream) input;
        }
    }

    @Override
    public void dispose() {
        super.dispose();
        resetLocal();
    }

    @Override
    public void reset() {
        super.reset();
        resetLocal();
    }

    private void resetLocal() {
        iis = null;
        dis = null;
        ds = null;
        streamMetaData = null;
        width = 0;
        height = 0;
        frames = 0;
        allocated = 0;
        dataType = 0;
        samples = 0;
        banded = false;
        bigEndian = false;
        swapByteOrder = false;
        pixelDataPos = 0L;
        pixelDataLen = 0;
        tsuid = null;
        pmi = null;
        compressed = false;
        if (reader != null) {
            reader.dispose();
            reader = null;
        }
        itemParser = null;
        siis = null;
        autoWindowCenter = null;
        autoWindowWidth = null;
    }

    @Override
    public ImageReadParam getDefaultReadParam() {
        return new DicomImageReadParam();
    }

    /**
     * Return a DicomStreamMetaData object that includes the DICOM header.
     * <b>WARNING:</b> If this class is used to read directly from a cache or
     * other location that contains uncorrected data, the DICOM header will have
     * the uncorrected data as well. That is, assume the DB has some fixes to
     * patient demographics. These will not usually be applied to the DICOM
     * files directly, so you can get the wrong information from the header.
     * This is not an issue if you know the DICOM is up to date, or if you use
     * the DB information as authoritative.
     */
    @Override
    public IIOMetadata getStreamMetadata() throws IOException {
        readMetaData();
        return streamMetaData;
    }

    /**
     * Gets any image specific meta data. This should return the image specific
     * blocks for enhanced multi-frame, but currently it merely returns null.
     */
    @Override
    public IIOMetadata getImageMetadata(int imageIndex) throws IOException {
        return null;
    }

    /**
     * Returns the number of regular images in the study. This excludes
     * overlays.
     */
    @Override
    public int getNumImages(boolean allowSearch) throws IOException {
        readMetaData();
        return frames;
    }

    /**
     * Reads the DICOM header meta-data, up to, but not including pixel data.
     * 
     * @throws IOException
     */
    private void readMetaData() throws IOException {
        if (iis == null) {
            throw new IllegalStateException("Input not set!");
        }
        if (ds != null) {
            return;
        }
        dis = new DicomInputStream(iis);
        DicomInputHandler ih = new StopTagInputHandler(Tag.PixelData);
        if( isSkipLargePrivate() ) {
        	ih = new SizeSkipInputHandler(ih);
        }
        dis.setHandler(ih);
        ds = dis.readDicomObject();
        streamMetaData = new DicomStreamMetaData();
        fixHeaderData(ds);
        streamMetaData.setDicomObject(ds);
        bigEndian = dis.getTransferSyntax().bigEndian();
        tsuid = ds.getString(Tag.TransferSyntaxUID);
        width = ds.getInt(Tag.Columns);
        height = ds.getInt(Tag.Rows);
        frames = ds.getInt(Tag.NumberOfFrames);
        allocated = ds.getInt(Tag.BitsAllocated, 8);
        stored = ds.getInt(Tag.BitsStored, allocated);
        banded = ds.getInt(Tag.PlanarConfiguration) != 0;
        dataType = allocated <= 8 ? DataBuffer.TYPE_BYTE
                : DataBuffer.TYPE_USHORT;
        samples = ds.getInt(Tag.SamplesPerPixel, 1);
        paletteColor = ColorModelFactory.isPaletteColor(ds);
        monochrome = ColorModelFactory.isMonochrome(ds);
        // Some images seem to omit the photometric interpretation - provide a default value here 
        pmi = ds.getString(Tag.PhotometricInterpretation, samples==3 ? "RGB" : "MONOCHROME2");

        if (dis.tag() == Tag.PixelData) {
        	streamMetaData.setPixelData(true);
            if (frames == 0)
                frames = 1;
            swapByteOrder = bigEndian && dis.vr() == VR.OW
                    && dataType == DataBuffer.TYPE_BYTE;
            if (swapByteOrder && banded) {
                throw new UnsupportedOperationException(
                        "Big Endian color-by-plane with Pixel Data VR=OW not implemented");
            }
            pixelDataPos = dis.getStreamPosition();
            pixelDataLen = dis.valueLength();
            compressed = pixelDataLen == -1;
            verifyTransferSyntax();
            if (compressed) {
                ImageReaderFactory f = ImageReaderFactory.getInstance();
                log.debug("Transfer syntax for image is " + tsuid
                        + " with image reader class " + f.getClass());
                f.adjustDatasetForTransferSyntax(ds, tsuid);
                clampPixelValues = allocated == 16 && stored < 12
                        && UID.JPEGExtended24.equals(tsuid);
            }
        }
        afterReadMetaData();
    }
    
    /** Over-ride method to do something with the metadata after it has been read - eg fix up
     * items or do other customizations.
     * @throws IOException
     */
    protected void afterReadMetaData() throws IOException {
        
    }
    
    /**
     * Verifies that if the transfer syntax is set to raw data, that the pixel data is not actually compressed.
     * @throws IOException
     */
    protected void verifyTransferSyntax() throws IOException{
    	// If the transfer syntax says this is raw data, make sure it isn't too small for that to be the case
    	if(UID.ImplicitVRLittleEndian.equals(tsuid) && pixelDataLen < calculateFrameLength()){
    		// Read the first few bytes of data to determine if this is a JPEG, and if so what kind.
    		byte[] ret = new byte[256];
    		long offset = pixelDataPos;
    		long cPos = iis.getStreamPosition();
    		iis.seek(offset);
    		iis.read(ret);
    		iis.seek(cPos);

    		final byte[] JPEG_MAGIC_BYTES = { (byte)0xFF, (byte)0xD8, (byte)0xFF };
    		final byte[] JPEG2K_MAGIC_BYTES = { (byte)0x00, (byte)0x00, (byte)0x00, 
    				(byte)0x0C, (byte)0x6A, (byte)0x50, (byte)0x20, (byte)0x20, (byte)0x0D, (byte)0x0A };
    		if(containsBytes(ret,JPEG_MAGIC_BYTES)){
    			// Now scan for some of the known SOF segment markers to determine the type of jpeg.
    			final byte[] JPEG_BASELINE1 = {(byte)0xFF, (byte)0xC0};
    			final byte[] JPEG_BASELINE2 = {(byte)0xFF, (byte)0xC2};
    			final byte[] JPEG_LOSSLESS1 = {(byte)0xFF, (byte)0xC3};
    			final byte[] JPEG_LOSSLESS2 = {(byte)0xFF, (byte)0xF7};
    			if(containsBytes(ret,JPEG_BASELINE1) || containsBytes(ret,JPEG_BASELINE2)){
    				tsuid = UID.JPEGBaseline1;
    			}else if(containsBytes(ret,JPEG_LOSSLESS1) || containsBytes(ret,JPEG_LOSSLESS2)){
    				tsuid = UID.JPEGLossless;
    			}else{
    				tsuid = UID.JPEGExtended24;
    			}
    			log.warn("Incorrect transfer syntax UID detected. Has been modified to "+tsuid);
    		}else if(containsBytes(ret,JPEG2K_MAGIC_BYTES)){
    			tsuid = UID.JPEG2000;
    			log.warn("Incorrect transfer syntax UID detected. Has been modified to "+tsuid);
    		}else{
    			log.error("Incorrect transfer syntax UID was provided, and a suitable replacement could not be found.");
    		}
    		
    	}
    }

    private boolean containsBytes(byte[] parent, byte[] sub){
    	if(parent.length == 0 || sub.length == 0) return false;
    	
    	boolean in = false;
    	
    	for(int i=0; i<parent.length; i++){
    		if(parent[i] == sub[0]){
				in = true;
    			for(int j=1; j<sub.length; j++){
    				if(sub[j] != parent[i+j]){
    					in = false;
    					break;
    				}
    			}
    			if(in)
    				break;
    		}
    	}
    	return in;
    }
    
    /**
     * Performs various fixes to the header data to fix up vendor specific bugs.
     * 
     * @todo Make this a plugin mechanism to do additional fixes.
     * @param dsFix
     */
    protected void fixHeaderData(DicomObject dsFix) {
    	String transfer = dsFix.getString(Tag.TransferSyntaxUID);
    	String pmi = dsFix.getString(Tag.PhotometricInterpretation);
    	
    	boolean isJpeg = UID.JPEGBaseline1.equals(transfer) || UID.JPEGLosslessNonHierarchical14.equals(transfer);
    	
    	// This is a fairly easy mistake for a vendor to make, recode to DICOM, but leave the photometric interpretation
    	if( ColorModelFactory.YBR_FULL_422.equals(pmi) && isJpeg ) {
    		log.info("Fix JPEG photometric on {}", dsFix.getString(Tag.SOPInstanceUID));
    		dsFix.putString(Tag.PhotometricInterpretation,null,ColorModelFactory.RGB);
    	}
	}

	public void readPostPixeldata() throws IOException{
    	readMetaData();
    	long currentPosition = dis.getStreamPosition();
    	   	
    	if (pixelDataPos > 0){
    		// There is pixeldata
    		if (pixelDataLen >= 0){
    			// last call already read the tag, but we want to init a new InputStream from here
    	    	dis.reset();
    	    	
    			// there is uncompressed pixeldata
    			long startPosition = pixelDataPos + pixelDataLen;  
    			iis.seek(startPosition);
    		} else {    	    			
    			// via siis
    			int imageIndex = frames - 1;
    			imageIndex = (imageIndex < 0) ? 0:imageIndex;
    			if (siis == null){
    				initCompressedImageReader(imageIndex);	
    			} 
    			itemParser.seekFooter();
    		}
    	} else {
    		// last call already read the tag, but we want to init a new InputStream from here
        	dis.reset();
    	}
    	
    	// Not reusing the earlier dicom input stream as it has a stop tag handler and we want to go past the pixeldata.
    	// Not explicitly closed - only the underlying stream gets closed.
    	@SuppressWarnings("resource")
        DicomInputStream postDis = new DicomInputStream(iis, TransferSyntax.valueOf(tsuid));       
        if( isSkipLargePrivate() ) {
        	DicomInputHandler dih = new SizeSkipInputHandler(null);
        	postDis.setHandler(dih);
        }        
        
		final DicomObject postPixelDs = postDis.readDicomObject();
    	if (postPixelDs != null && !postPixelDs.isEmpty()) {
    	    
    	    afterReadPostPixelData(postPixelDs);
    	    
    		// Note the postPixelDs.copyTo(ds) does not work because the copy does not handle the 
    		// SkippedDicomElements correctly    		
    		ds = new CombineDicomObject(ds, postPixelDs);
        	streamMetaData.setDicomObject(ds);
    	}
    	
    	// reset the stream
    	iis.seek(currentPosition);    	
    }
    
    /**
     * Called as part of {@link #readPostPixeldata()} if there actually is data after {@link Tag#PixelData}.
     * @param postPixelData Only contains the data after the pixel data.
     */
    protected void afterReadPostPixelData(DicomObject postPixelData) throws IOException {
        
    }

    /**
     * Sets the input for the image reader.
     * 
     * @param imageIndex
     *            The Dicom frame index, or overlay number
     * @throws IOException
     */
    protected void initImageReader(int imageIndex) throws IOException {
        readMetaData();
        if (reader == null) {
            if (compressed) {
                initCompressedImageReader(imageIndex);
            } else {
                initRawImageReader();
            }
        }
        // Reset the input stream location if required, and reset the reader if
        // required
        if (compressed) {
            if (this.siis == null) {
        		this.siis = new SegmentedImageInputStream(iis, itemParser);		
        	} 		
            itemParser.seekFrame(siis, imageIndex);
            reader.setInput(siis, false);
        }
    }

    private void initCompressedImageReader(int imageIndex) throws IOException {
        ImageReaderFactory f = ImageReaderFactory.getInstance();
        this.reader = f.getReaderForTransferSyntax(tsuid);
        this.itemParser = new ItemParser(dis, iis, frames, tsuid);
        this.siis = new SegmentedImageInputStream(iis, itemParser);
        this.itemParser.seekFrame(siis, imageIndex);
    }

    private void initRawImageReader() {
        long[] frameOffsets = new long[frames];
        int frameLen = calculateFrameLength();
        frameOffsets[0] = pixelDataPos;
        for (int i = 1; i < frameOffsets.length; i++) {
            frameOffsets[i] = frameOffsets[i - 1] + frameLen;
        }
        Dimension[] imageDimensions = new Dimension[frames];
        Arrays.fill(imageDimensions, new Dimension(width, height));
        RawImageInputStream riis = new RawImageInputStream(iis,
                createImageTypeSpecifier(), frameOffsets, imageDimensions);
        riis.setByteOrder(bigEndian ? ByteOrder.BIG_ENDIAN
                                    : ByteOrder.LITTLE_ENDIAN);
        reader = ImageIO.getImageReadersByFormatName("RAW").next();
        reader.setInput(riis);
    }

	public int calculateFrameLength() {		
		if (pmi.endsWith("422") || pmi.endsWith("420")){
			int calcWidth = width;
			int calcHeight = height;
			int extraRowSamples = 0;
			int extraColSamples = 0;
			if (pmi.endsWith("422")){ 

				if (width%2 != 0){
					// odd number of columns
					calcWidth --;			
					extraColSamples = 3;	
				}
				return (calcWidth * calcHeight * 2 +  
						calcHeight * extraColSamples) * (allocated >> 3);
			}

			if (pmi.endsWith("420")){
				calcHeight = calcHeight/2;
				if (width%2 != 0){
					// odd number of columns
					calcWidth --;			
					extraColSamples = 4;	
				}
				if (height%2 != 0){
					// odd number of rows					
					extraRowSamples = 2;
				}
				
				int length = (calcWidth * calcHeight * 3 + 
						calcWidth * extraRowSamples + 
						calcHeight * extraColSamples) * (allocated >> 3);
				
				if (width%2 != 0 && height%2 != 0){
					length = length + 3; 
				}

				return length;
			}
		}					
		return	width * height * samples * (allocated >> 3);	
	}

    /** Create an image type specifier for the entire image */
    protected ImageTypeSpecifier createImageTypeSpecifier() {
        ColorModel cm = ColorModelFactory.createColorModel(ds);
        SampleModel sm = createSampleModel();
        return new ImageTypeSpecifier(cm, sm);
    }

    private SampleModel createSampleModel() {
        if (samples == 1) {
            return new PixelInterleavedSampleModel(dataType, width, height, 1,
                    width, OFFSETS_0);
        }

        // samples == 3
        if (banded) {
            return new BandedSampleModel(dataType, width, height, width,
                    OFFSETS_0_1_2, OFFSETS_0_0_0);
        }

        if(  pmi.endsWith("422" ) ) {
            return new PartialComponentSampleModel(width, height, 2, 1);
        }

        if( (!compressed) && pmi.endsWith("420") ) {
            return new PartialComponentSampleModel(width, height, 2, 2);
        }
        
        return new PixelInterleavedSampleModel(dataType, width, height, 3,
                width * 3, OFFSETS_0_1_2);
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        readMetaData();
        if (OverlayUtils.isOverlay(imageIndex))
            return OverlayUtils.getOverlayHeight(ds, imageIndex);
        return height;
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        readMetaData();
        if (OverlayUtils.isOverlay(imageIndex))
            return OverlayUtils.getOverlayWidth(ds, imageIndex);
        return width;
    }

    public String getTransferSyntaxUID() throws IOException {
        readMetaData();
        return tsuid;
    }
    
    /**
     * Exposes the window center value that was determined during the last
     * {@link #read(int, ImageReadParam)} call.
     * 
     * @return the window center value, or <code>null</code> if auto windowing was not applied.
     */
    public Float getAutoWindowCenter() {
        return autoWindowCenter;
    }

    /**
     * Exposes the window width value that was determined during the last
     * {@link #read(int, ImageReadParam)} call.
     * 
     * @return the window width value, or <code>null</code> if auto windowing was not applied.
     */
    public Float getAutoWindowWidth() {
        return autoWindowWidth;
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex)
            throws IOException {
        // Index changes from 1 to 0 as the Dicom frames start to count at 1
        // ImageReader expects the first frame to be 0.
        initImageReader(0);
        return reader.getImageTypes(0);
    }

    @Override
    public boolean canReadRaster() {
        return true;
    }

    /**
     * Read the raw raster data from the image, without any LUTs being applied.
     * Cannot read overlay data, as it isn't clear what the raster format should
     * be for those.  
     */
    @Override
    public Raster readRaster(int imageIndex, ImageReadParam param) throws IOException {
        initImageReader(imageIndex);
        if (param == null) {
            param = getDefaultReadParam();
        }
        if (compressed) {
            ImageReadParam param1 = reader.getDefaultReadParam();
            copyReadParam(param, param1);
            Raster raster = decompressRaster(imageIndex, param1);
            if (clampPixelValues)
                clampPixelValues(raster);
            return raster;
        }
        if( pmi.endsWith("422") || pmi.endsWith("420") ) {
            log.debug("Using a 422/420 partial component image reader.");
            if( param.getSourceXSubsampling()!=1 
                    || param.getSourceYSubsampling()!=1 
                    || param.getSourceRegion()!=null ) {
                log.warn("YBR_*_422 and 420 reader does not support source sub-sampling or source region.");
                throw new UnsupportedOperationException("Implement sub-sampling/soure region.");
            }
            SampleModel sm = createSampleModel();
            WritableRaster wr = Raster.createWritableRaster(sm, new Point());
            
            DataBufferByte dbb = (DataBufferByte) wr.getDataBuffer();
            byte[] data = dbb.getData();
            int frameLength = calculateFrameLength();
            log.debug("Seeking to "+(pixelDataPos + imageIndex * frameLength)+" and reading "+data.length+" bytes.");           
            iis.seek(pixelDataPos + imageIndex * frameLength);
            iis.read(data, 0, frameLength);
            if (swapByteOrder) {
                ByteUtils.toggleShortEndian(data);
            }
            return wr;
        }
        Raster raster = reader.readRaster(imageIndex, param);
        if (swapByteOrder) {
            ByteUtils.toggleShortEndian(
                    ((DataBufferByte)raster.getDataBuffer()).getData());
        }
        return raster;
    }
    
    private void clampPixelValues(Raster raster) {
        int maxVal = -1 >>> (32 - stored);
        short[] data = ((DataBufferUShort) raster.getDataBuffer()).getData();
        for (int i = 0; i < data.length; i++)
            if (data[i] > maxVal)
                data[i] = (short) maxVal;
    }

    /**
     * Reads the provided image as a buffered image. It is possible to read
     * image overlays by providing the 0x60000000 number associated with the
     * overlay. Otherwise, the imageIndex must be in the range
     * 0..numberOfFrames-1, or 0 for a single frame image. Overlays can be read
     * from PR objects or other types of objects in addition to image objects.
     * param can be used to specify GSPS to apply to the image, or to override
     * the default window level values, or to return the raw image.
     */
    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
        if (OverlayUtils.isOverlay(imageIndex)) {
            readMetaData();
            String rgbs = (param != null) ? ((DicomImageReadParam) param)
                    .getOverlayRGB() : null;
            return OverlayUtils.extractOverlay(ds, imageIndex, this, rgbs);
        }
        initImageReader(imageIndex);
        if (param == null) {
            param = getDefaultReadParam();
        }
        BufferedImage bi;
        if (compressed) {
            ImageReadParam param1 = reader.getDefaultReadParam();
            copyReadParam(param, param1);
            bi = reader.read(0, param1);
            if (clampPixelValues)
                clampPixelValues(bi.getRaster());
            postDecompress();
            if (paletteColor && bi.getColorModel().getNumComponents() == 1) {
                bi = new BufferedImage(ColorModelFactory.createColorModel(ds),
                        bi.getRaster(), false, null);
            }
        } else if( pmi.endsWith("422") || pmi.endsWith("420") ) {
            bi = readYbr400(imageIndex, param);
        } else {
            bi = reader.read(imageIndex, param);
            if (swapByteOrder) {
                ByteUtils.toggleShortEndian(
                        ((DataBufferByte)bi.getRaster().getDataBuffer()).getData());
            }
        }
        if (monochrome) {
            WritableRaster raster = bi.getRaster();
            LookupTable lut = createLut((DicomImageReadParam) param,
                    imageIndex + 1, raster);
            if (lut != null) {
                WritableRaster dest = raster;
                if (dest.getDataBuffer().getDataType() != DataBuffer.TYPE_BYTE
                        && (lut instanceof ByteLookupTable)) {
                    BufferedImage ret = new BufferedImage(bi.getWidth(), bi
                            .getHeight(), BufferedImage.TYPE_BYTE_GRAY);
                    dest = ret.getRaster();
                    bi = ret;
                }
                DataBuffer destData = dest.getDataBuffer();
                lut.lookup(raster, dest);
                if (destData.getDataType() == DataBuffer.TYPE_SHORT) {
                    ColorModel cm = bi.getColorModel();
                    short[] ss = ((DataBufferShort) destData).getData();
                    return new BufferedImage(cm, Raster.createWritableRaster(
                            raster.getSampleModel(), new DataBufferUShort(ss,
                                    ss.length), null),
                            cm.isAlphaPremultiplied(),
                            new Hashtable<Object,Object>());
                }
            }
        }
        return bi;
    }

    /**
     * Reads the Ybr 422 and 420 type images.  Sub-samples after reading as necessary.
     * 
     * @param imageIndex
     * @param param
     * @return
     * @throws IOException
     */
    private BufferedImage readYbr400(int imageIndex, ImageReadParam param) throws IOException {
        ImageReadParam useParam = param;
        Rectangle sourceRegion = param.getSourceRegion();
        if( param.getSourceXSubsampling()!=1 
                || param.getSourceYSubsampling()!=1 
                || sourceRegion!=null ) {
            useParam = getDefaultReadParam();
        }
        BufferedImage bi;        
        WritableRaster wr = (WritableRaster) readRaster(imageIndex, useParam);
        bi = new BufferedImage(ColorModelFactory.createColorModel(ds),
                wr, false, null);
        if( useParam==param ) return bi;
        return subsampleRGB(bi,sourceRegion,param.getSourceXSubsampling(),param.getSourceYSubsampling());
    }
 
    /** Sub-samples RGB buffered images when the reader doesn't support it.  */
    public static BufferedImage subsampleRGB(BufferedImage src, Rectangle sourceRegion, int subSampleX, int subSampleY) {
        if( sourceRegion==null ) sourceRegion = new Rectangle(src.getWidth(), src.getHeight());
        int dWidth = (int)Math.ceil((double)sourceRegion.width/subSampleX);
        int dHeight = (int)Math.ceil((double)sourceRegion.height/subSampleY);
        
    	BufferedImage dest = createRGBBufferedImage(dWidth, dHeight);

        int[] srcRgb = new int[src.getWidth()];
        int[] destRgb = new int[dWidth];
        int maxY = sourceRegion.y+sourceRegion.height;
        int maxX =  sourceRegion.x+sourceRegion.width;
        int destY=0;
        for (int iy = sourceRegion.y; iy < maxY; iy += subSampleY, destY++) {
            srcRgb = src.getRGB(sourceRegion.x, iy, sourceRegion.width, 1, srcRgb, 0, src.getWidth());
            if( subSampleX==1 ) {
            	dest.setRGB(0, destY, dWidth, 1, srcRgb, 0, src.getWidth());
            } else {
                int destX = 0;
                for(int ix=sourceRegion.x; ix < maxX; ix += subSampleX) {
                    destRgb[destX++] = srcRgb[ix];
                }
                dest.setRGB(0, destY, dWidth, 1, destRgb, 0, dWidth);
            }
        }
        return dest;
    }
    
    /**
     * Creates a BufferedImage with a custom color model that can be used to store
     * 3 channel RGB data in a byte array data buffer
     */
    public static BufferedImage createRGBBufferedImage(int destWidth, int destHeight) {
    	ColorSpace cs = ColorSpace.getInstance(ColorSpace.CS_sRGB);
    	ColorModel cm = new ComponentColorModel(cs, false, false,
                Transparency.OPAQUE, DataBuffer.TYPE_BYTE);
    	WritableRaster r = cm.createCompatibleWritableRaster(destWidth, destHeight);
    	BufferedImage dest = new BufferedImage(cm, r, false, null);
    	
    	return dest;
    }

    /**
     * Reads the bytes for the given image as raw data. Useful when copying the
     * image data unchanged to a new file/location etc, but some values are
     * being changed in the header, or some images are being excluded.
     */
    public byte[] readBytes(int imageIndex, ImageReadParam param) throws IOException {
        initImageReader(imageIndex);
        if (compressed) {
            return itemParser.readFrame(siis, imageIndex);
        }

        int frameLen = calculateFrameLength();
        byte[] ret = new byte[frameLen];
        long offset = pixelDataPos + imageIndex * (long) frameLen;
        iis.seek(offset);
        iis.read(ret);
        return ret;
    }
    
    /**
     * Gets the correct frame length for the given instance - can be used instead of the  
     * @param imageIndex
     * @return
     * @throws IOException
     */
    public int getFrameLength(int imageIndex) throws IOException {
    	initImageReader(imageIndex);
    	if( compressed) {
    		return itemParser.getFrameLength(imageIndex);
    	}
    	return calculateFrameLength();
    }

    /**
     * Before read image stream, seek the right frame position first.
     * Note this method is called ONLY if you want to read the data directly
     * from ImageInputStream from yourself. 
     * @param imageIndex
     * @param param
     * @return The length of image data that will be read from stream
     */
    private int seekFrameBeforeForReadStream(
            int imageIndex, ImageReadParam param)throws IOException {
        initImageReader(imageIndex);
        if (compressed) {
            return itemParser.seekImageFrameBeforeReadStream(siis, imageIndex);
        }
        int frameLen = calculateFrameLength();
        long offset = pixelDataPos + imageIndex * (long) frameLen;
        iis.seek(offset);
        return frameLen;
    }
       
    
    /**
     * Return the underlying Input set.
     * 
     * @return
     */
    public synchronized ImageInputStream getImageInputStream()	{
    	return iis;
    }
     
    
    /**
     * Fetch the offset and length of the given imageIndex
     * 
     * @param imageIndex
     * @return
     * @throws IOException
     */
    public synchronized long[] getImageInputStreamOffsetLength(int imageIndex) throws IOException	{
    	readMetaData();
        if (reader == null) {
            if (compressed) {
                ImageReaderFactory f = ImageReaderFactory.getInstance();
                this.reader = f.getReaderForTransferSyntax(tsuid);
                this.itemParser = new ItemParser(dis, iis, frames, tsuid);                
            } else {
                initRawImageReader();
            }
        }    
        if (compressed) {
        	return itemParser.fetchFrameOffsetAndLength(imageIndex);
        }
        long frameLen = calculateFrameLength();
        long offsetp = pixelDataPos + imageIndex * (long) frameLen;
        return new long[] {offsetp, frameLen};        
    }
    
    /**
     * Get the ImageInputStream to read. 
     * @return
     */
    public ImageInputStream getImageInputStream(int imageIndex, ImageReadParam param)
    throws IOException {
        seekFrameBeforeForReadStream(imageIndex, param);
        if (compressed) {
            return siis;
        }
        return iis;
    }
    
    /** Makes a copy of the incoming parameters into the parameter that is used for the nested image reader */
    protected void copyReadParam(ImageReadParam src, ImageReadParam dst) {
        dst.setDestination(src.getDestination());
        dst.setSourceRegion(src.getSourceRegion());
        dst.setSourceSubsampling(src.getSourceXSubsampling(), src
                .getSourceYSubsampling(), src.getSubsamplingXOffset(), src
                .getSubsamplingYOffset());
        dst.setDestinationOffset(src.getDestinationOffset());
        if (ImageReaderFactory.getInstance().needsImageTypeSpecifier(tsuid)) {
            dst.setDestinationType(createImageTypeSpecifier());
        }
        
        //if lossy jpeg2000 and using the jai j2k reader
        //a workaround for http://java.net/jira/browse/JAI_IMAGEIO_CORE-175
        if(J2KIMAGE_READ_PARAM_CLASS != null && J2KIMAGE_READ_PARAM_CLASS.isAssignableFrom(dst.getClass())) {
            try {
                J2KIMAGE_READ_PARAM_SET_RESOLUTION.invoke(dst, Integer.MAX_VALUE);
            } catch (Exception e) {
                log.info("A Failure occurred attempting to set the resolution for a jai J2KImageReadParam. This may result in degraded image quality.", e);
            }
        }
    }
    
    private Raster decompressRaster(int imageIndex, ImageReadParam param) throws IOException {
        if (!reader.canReadRaster()) {
            BufferedImage bi = reader.read(0, param);
            postDecompress();
            return bi.getRaster();
        }
        Raster raster = reader.readRaster(0, param);
        postDecompress();
        return raster;
    }

    protected void postDecompress() {
        // workaround for Bug in J2KImageReader and
        // J2KImageReaderCodecLib.setInput()
        if (reader.getClass().getName().startsWith(J2KIMAGE_READER)) {
            reader.dispose();
            ImageReaderFactory f = ImageReaderFactory.getInstance();
            reader = f.getReaderForTransferSyntax(tsuid);
        } else {
            reader.reset();
        }
    }

    /**
     * Return the complete lookup table to apply to the image data. This
     * comprises the Modality LUT, VOI LUT and Presentation LUT. The Modality
     * LUT can be represented as rescale slope/intercept, and the VOI LUT can be
     * represented as window width/center/type. Presentation LUT will come from
     * the specified values in the image read parameter, and is designed to turn
     * p-values into DDLs (digital driving levels).
     * 
     * @param param
     * @param raster
     * @return Complete lookup table to apply to the image.
     */
    private LookupTable createLut(DicomImageReadParam param, int frame, Raster raster) {
        short[] pval2gray = param.getPValue2Gray();
        DicomObject pr = param.getPresentationState();
        float c = param.getWindowCenter();
        float w = param.getWindowWidth();
        String vlutFct = param.getVoiLutFunction();
        if (param.isAutoWindowing()) {
            DicomObject voiObj = VOIUtils.selectVoiObject(ds, pr, frame);
            if (voiObj == null) {
                float[] cw = VOIUtils.getMinMaxWindowCenterWidth(ds, pr, frame,
                        raster);
                c = cw[0];
                w = cw[1];
                vlutFct = LookupTable.LINEAR;
                this.autoWindowCenter = Float.valueOf(c);
                this.autoWindowWidth = Float.valueOf(w);
            }
        }

        return LookupTable.createLutForImageWithPR(ds, pr, frame, c, w,
                vlutFct, 8, pval2gray);
    }

	public boolean isSkipLargePrivate() {
		return skipLargePrivate;
	}

	public void setSkipLargePrivate(boolean skipLargePrivate) {
		this.skipLargePrivate = skipLargePrivate;
	}
}
