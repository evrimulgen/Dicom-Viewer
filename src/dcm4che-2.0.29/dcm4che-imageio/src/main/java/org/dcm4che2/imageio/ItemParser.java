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

package org.dcm4che2.imageio;

import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

import javax.imageio.stream.ImageInputStream;

import org.dcm4che2.data.Tag;
import org.dcm4che2.data.UID;
import org.dcm4che2.io.DicomInputStream;
import org.dcm4che2.util.TagUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.media.imageio.stream.SegmentedImageInputStream;
import com.sun.media.imageio.stream.StreamSegment;
import com.sun.media.imageio.stream.StreamSegmentMapper;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 * @version $Revision$ $Date$
 * @since Aug 6, 2007
 */
public class ItemParser implements StreamSegmentMapper {

    private static final Logger log = LoggerFactory.getLogger(ItemParser.class);

    private static final HashSet<String> JPEG_TS = new HashSet<String>(
            Arrays.asList(new String[] { UID.JPEGBaseline1, UID.JPEGExtended24,
                    UID.JPEGExtended35Retired,
                    UID.JPEGSpectralSelectionNonHierarchical68Retired,
                    UID.JPEGSpectralSelectionNonHierarchical79Retired,
                    UID.JPEGFullProgressionNonHierarchical1012Retired,
                    UID.JPEGFullProgressionNonHierarchical1113Retired,
                    UID.JPEGLosslessNonHierarchical14,
                    UID.JPEGLosslessNonHierarchical15Retired,
                    UID.JPEGExtendedHierarchical1618Retired,
                    UID.JPEGExtendedHierarchical1719Retired,
                    UID.JPEGSpectralSelectionHierarchical2022Retired,
                    UID.JPEGSpectralSelectionHierarchical2123Retired,
                    UID.JPEGFullProgressionHierarchical2426Retired,
                    UID.JPEGFullProgressionHierarchical2527Retired,
                    UID.JPEGLosslessHierarchical28Retired,
                    UID.JPEGLosslessHierarchical29Retired, UID.JPEGLossless,
                    UID.JPEGLSLossless, UID.JPEGLSLossyNearLossless,
                    UID.JPEG2000LosslessOnly, UID.JPEG2000 }));
    
    private static final HashSet<String> VIDEO_TS = new HashSet<String>(
        Arrays.asList(new String[] { UID.MPEG2, UID.MPEG2MainProfileHighLevel,
        		UID.MPEG4AVCH264BDCompatibleHighProfileLevel41, 
        		UID.MPEG4AVCH264HighProfileLevel41 }));

    public static final class Item {

        public final int offset;

        public final long startPos;

        public final int length;
        
        public Item(int offset, long startPos, int length) {
            this.offset = offset;
            this.startPos = startPos;
            this.length = length;
        }

        final int nextOffset() {
            return offset + length;
        }

        final long nextItemPos() {
            return startPos + length;
        }

        @Override
        public String toString() {
            return "Item[off=" + offset + ", pos=" + startPos + ", len="
                    + length + "]";
        }
    }

    private final ArrayList<Item> items = new ArrayList<Item>();

    private final DicomInputStream dis;

    private final ImageInputStream iis;

    private final ArrayList<Item> firstItemOfFrame;

    private final int numberOfFrames;

    private final boolean rle;

    private final boolean jpeg;

    private long[] basicOffsetTable;

    private final byte[] soi = new byte[2];

    private boolean lastItemSeen = false;

    private int frame;

    public ItemParser(DicomInputStream dis, ImageInputStream iis,
            int numberOfFrames, String tsuid) throws IOException {
        this.dis = dis;
        this.iis = iis;
        // Handle video type data - eventually there should be another way to compute this
        if( VIDEO_TS.contains(tsuid) ) numberOfFrames = 1;
        this.numberOfFrames = numberOfFrames; 
        this.firstItemOfFrame = new ArrayList<Item>(numberOfFrames);
        this.rle = UID.RLELossless.equals(tsuid);
        this.jpeg = !rle && JPEG_TS.contains(tsuid);
        dis.readHeader();
        int offsetTableLen = dis.valueLength();
        if (offsetTableLen != 0) {
            if (offsetTableLen != numberOfFrames * 4) {
                log.warn("Skip Basic Offset Table with illegal length: "
                        + offsetTableLen + " for image with " + numberOfFrames
                        + " frames!");
                iis.skipBytes(offsetTableLen);
            } else {
                basicOffsetTable = new long[numberOfFrames];
                long highWord = 0, highInc = 0x100000000L;
                iis.setByteOrder(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < basicOffsetTable.length; i++) {
                    basicOffsetTable[i] = highWord | (0xFFFFFFFF & iis.readInt());
                    // Allow wrapping of the counter in case we get more than 2^32 sized offsets.
                    if( i>0 && basicOffsetTable[i] < basicOffsetTable[i-1] ) {
                    	highWord += highInc;
                    	basicOffsetTable[i] += highInc;
                    }
                    
                }
            }
        }
        next();
        
        // Create the items/first items based on the basic offset table.
        if( basicOffsetTable!=null ) {
            Item firstItem = firstItemOfFrame.get(0);
            Item prev = firstItemOfFrame.get(firstItemOfFrame.size()-1);
            for(int i=firstItemOfFrame.size(); i<basicOffsetTable.length-1; i++) {
        		Item addItem = new Item(prev.nextOffset(), firstItem.startPos + basicOffsetTable[i],
                        (int) (basicOffsetTable[i+1]-basicOffsetTable[i]-8));
        		addFirstItemOfFrame(addItem);
        		items.add(addItem);
        		prev = addItem;
        	}
        }        
    }

    public int getNumberOfDataFragments() {
        while (!lastItemSeen)
            next();
        return items.size();
    }

    private Item getFirstItemOfFrame(int frame) throws IOException {
        while (firstItemOfFrame.size() <= frame) {
            if (next() == null)
                throw new IOException("Could not detect first item of frame #"
                        + (frame+1));
        }
        return firstItemOfFrame.get(frame);
    }

    private Item next() {
        if (lastItemSeen)
            return null;
        try {
            if (!items.isEmpty())
                iis.seek(last().nextItemPos());
            dis.readHeader();
            if (log.isDebugEnabled())
                log.debug("Read " + TagUtils.toString(dis.tag()) + " #"
                        + dis.valueLength());
            if (dis.tag() == Tag.Item) {
                Item item = new Item(
                        items.isEmpty() ? 0 : last().nextOffset(),
                        iis.getStreamPosition(),
                        dis.valueLength());
                if (items.isEmpty() || rle) {
                    addFirstItemOfFrame(item);
                } else if (firstItemOfFrame.size() < numberOfFrames) {
                    if (basicOffsetTable != null) {
                        Item firstItem = firstItemOfFrame.get(0);
                        int frame = firstItemOfFrame.size();
                        if (item.startPos == firstItem.startPos + basicOffsetTable[frame]) {
                            if (log.isDebugEnabled()) {
                                log.debug("Start position of item #"
                                        + (items.size()+1) + " matches "
                                        + (frame+1)
                                        + ".entry of Basic Offset Table.");
                            }
                            addFirstItemOfFrame(item);
                        }
                    } else if (jpeg) {
                        iis.read(soi, 0, 2);
                        if (soi[0] == (byte) 0xFF && (soi[1] == (byte) 0xD8
                                                   || soi[1] == (byte) 0x4F)) {
                            if (log.isDebugEnabled()) {
                                log.debug("Detect JPEG SOI/SOC at item #"
                                        + (items.size()+1));
                            }
                            addFirstItemOfFrame(item);
                        }
                        iis.seek(item.startPos);
                    }
                }
                items.add(item);
                return item;
            }
        } catch (IOException e) {
            log.warn("i/o error reading next item:", e);
        }
        if (dis.tag() != Tag.SequenceDelimitationItem
                || dis.valueLength() != 0) {
            log.warn("expected (FFFE,E0DD) #0 but read "
                    + TagUtils.toString(dis.tag()) + " #" + dis.valueLength());
        }
        lastItemSeen = true;
        return null;
    }

    private void addFirstItemOfFrame(Item item) {
        if (log.isDebugEnabled()) {
            log.debug("Detect item #" + (items.size()+1)
                    + " as first item of frame #"
                    + (firstItemOfFrame.size()+1));
        }
        firstItemOfFrame.add(item);
    }

    private Item last() {
        return items.get(items.size() - 1);
    }

    public StreamSegment getStreamSegment(long pos, int len) {
        StreamSegment retval = new StreamSegment();
        getStreamSegment(pos, len, retval);
        return retval;
    }
    
    protected int findItemPosition(long offset) {
    	int s=0, e=items.size()-1;
    	int m = (s+e)/2;
    	while( e>s ) {
    		Item test = items.get(m);
    		if( test.offset==offset ) return m;
    		if( test.offset < offset ) s = m;
    		else e = m-1;
    		m = (s+e+1)/2;
    	}
    	return m;
    }
    protected int findItemPosition(Item item) {
    	return findItemPosition(item.offset);
    }

    public void getStreamSegment(long pos, int len, StreamSegment seg) {
        if (log.isDebugEnabled())
            log.debug("getStreamSegment(pos=" + pos + ", len=" + len + ")");
        if (isEndOfFrame(pos)) {
            setEOF(seg);
            return;
        }
        Item item = items.get(findItemPosition(pos));
        seg.setStartPos(item.startPos + pos - item.offset);
        seg.setSegmentLength(Math.min((int) (item.offset + item.length - pos),
                len));
        if( seg.getSegmentLength() == 0 ) {
            setEOF(seg);
        }
        if (log.isDebugEnabled())
            log.debug("return StreamSegment[start=" + seg.getStartPos()
                    + ", len=" + seg.getSegmentLength() + "]");
    }

    private boolean isEndOfFrame(long pos) {
    	if( frame+1 < firstItemOfFrame.size() ) {
            return firstItemOfFrame.get(frame+1).offset <= pos;
    	}
    	if( numberOfFrames>1 ) {
    		// Going through the items in multiframe doesn't really work correctly, so avoid it.
    		return false;
    	}
    	for(Item i : items) {
    		pos -= i.length;
    		if( pos<0 ) return false;
    	}
    	if( lastItemSeen ) return true;
    	next();
    	return false;
    }

    private void setEOF(StreamSegment seg) {
        seg.setSegmentLength(-1);
        if (log.isDebugEnabled())
            log.debug("return StreamSegment[start=" + seg.getStartPos()
                    + ", len=-1]");
    }

    public void seekFrame(SegmentedImageInputStream siis, int frame)
            throws IOException {
        if (log.isDebugEnabled())
            log.debug("seek frame #" + (frame+1));
        Item item = getFirstItemOfFrame(frame);
        siis.seek(item.offset);
        iis.seek(item.startPos);
        this.frame = frame;
        if (log.isDebugEnabled())
            log.debug("seek " + item);
    }

    public byte[] readFrame(SegmentedImageInputStream siis, int frame)
            throws IOException {
        int frameSize = getFrameLength(frame);
        byte[] data = new byte[frameSize];
        seekFrame(siis, frame);
        siis.readFully(data);
        return data;
    }

    /**
     * Gets the frame length for the given frame - note that the segmented frame size is incorrect, in general, so use this method instead.
     * @param frame
     * @return length of the frame.
     * @throws IOException
     */
	public int getFrameLength(int frame) throws IOException {
		Item item = getFirstItemOfFrame(frame);
        int frameSize = item.length;
        int firstItemOfNextFrameIndex = frame + 1 < numberOfFrames
                ? items.indexOf(getFirstItemOfFrame(frame + 1))
                : getNumberOfDataFragments();
        for (int i = findItemPosition(item.offset) + 1; i < firstItemOfNextFrameIndex; i++) {
            frameSize += items.get(i).length;
        }
		return frameSize;
	}   

    /**
     * Fetch the offset and  length of the provided frame
     *  
     * @param frame
     * @return
     * @throws IOException
     */
    public long[] fetchFrameOffsetAndLength(int frame)
    throws IOException {
    	long offsetLength[] = new long [2];
        int frameSize = getFrameLength(frame);
        Item item = getFirstItemOfFrame(frame);
        offsetLength [0] = item.startPos;
        offsetLength [1] = frameSize;
        return offsetLength;
    }	
	
    /**
     * seekFrame to right frame in order for ImageInputStream to read 
     * @param siis
     * @param frame
     * @return
     * @throws IOException
     */
    public int seekImageFrameBeforeReadStream(SegmentedImageInputStream siis, int frame)
    throws IOException {
        int frameSize = getFrameLength(frame);
        seekFrame(siis, frame);
        return frameSize;
    }

    public void seekFooter() throws IOException {
        iis.seek(last().nextItemPos());
        dis.readHeader();
    }
}
