/*
 * msgparser - http://auxilii.com/msgparser
 * Copyright (C) 2007  Roman Kurmanowytsch
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 */
package com.auxilii.msgparser;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import com.auxilii.msgparser.attachment.Attachment;
import com.auxilii.msgparser.attachment.FileAttachment;
import com.auxilii.msgparser.attachment.MsgAttachment;

/**
 * Main parser class that does the actual
 * parsing of the Outlook .msg file. It uses the
 * <a href="http://poi.apache.org/poifs/">POI</a>
 * library for parsing the .msg container file
 * and is based on a description posted by
 * Peter Fiskerstrand at
 * <a href="http://www.fileformat.info/format/outlookmsg/">fileformat.info</a>.
 * <p>
 * It parses the .msg file and stores the information
 * in a {@link Message} object. Attachments are
 * put into an {@link FileAttachment} object. Hence, please
 * keep in mind that the complete mail is held in the memory!
 * If an attachment is another .msg file, this
 * attachment is not processed as a normal attachment
 * but rather included as a {@link MsgAttachment}. This
 * attached mail is, again, a {@link Message} object
 * and may have further attachments and so on.
 * <p>
 * Note: this code has not been tested on a wide
 * range of .msg files. Use in production level
 * (as in any other level) at your own risk.
 * <p>
 * Usage:<br>
 * <code>
 * MsgParser msgp = new MsgParser();<br>
 * Message msg = msgp.parseMsg("test.msg");
 * </code>
 * @author roman.kurmanowytsch
 */
public class MsgParser {
    /**
     * Parses a .msg file provided in the specified file.
     *
     * @param msgFile The .msg file.
     * @return A {@link Message} object representing the .msg file.
     * @throws IOException Thrown if the file could not be loaded or parsed.
     */
    public Message parseMsg(File msgFile) throws IOException {
        try (InputStream stream = new FileInputStream(msgFile)) {
            return this.parseMsg(stream);
        }
    }

    /**
     * Parses a .msg file provided in the specified file.
     *
     * @param msgFile The .msg file as a String path.
     * @return A {@link Message} object representing the .msg file.
     * @throws IOException Thrown if the file could not be loaded or parsed.
     */
    public Message parseMsg(String msgFile) throws IOException {
        try (InputStream stream = new FileInputStream(msgFile)) {
            return this.parseMsg(stream);
        }
    }

    /**
     * Parses a .msg file provided by an input stream.
     *
     * @param msgFileStream The .msg file as a InputStream.
     * @return A {@link Message} object representing the .msg file.
     * @throws IOException Thrown if the file could not be loaded or parsed.
     */
    public Message parseMsg(InputStream msgFileStream) throws IOException {
        // the .msg file, like a file system, contains directories
        // and documents within this directories
        // we now gain access to the root node
        // and recursively go through the complete 'filesystem'.
        POIFSFileSystem fs = new POIFSFileSystem(msgFileStream);
        DirectoryEntry dir = fs.getRoot();
        Message msg = new Message();
        parseMsg(dir, msg);
        return msg;
    }

    private void parseMsg(DirectoryEntry dir, Message msg) throws IOException {
        DocumentEntry propertyEntry = (DocumentEntry) dir.getEntry("__properties_version1.0");
        try ( DocumentInputStream propertyStream = new DocumentInputStream(propertyEntry)) {
            propertyStream.skip(8);
            int nextRecipientId = propertyStream.readInt();
            int nextAttachmentId = propertyStream.readInt();
            int recipientCount = propertyStream.readInt();
            int attachmentCount = propertyStream.readInt();
            boolean topLevel = dir.getParent() == null;
            if (topLevel) {
                propertyStream.skip(8);
            }

            for (int index = 0; index < recipientCount; index++) {
                DirectoryEntry entry = (DirectoryEntry) dir.getEntry(String.format("__recip_version1.0_#%08X", index));
                parseRecipient(entry, msg);
            }
            for (int index = 0; index < attachmentCount; index++) {
                DirectoryEntry entry = (DirectoryEntry) dir.getEntry(String.format("__attach_version1.0_#%08X", index));
                parseAttachment(entry, msg);
            }
            while (propertyStream.available() > 0) {
                msg.setProperty(new Property(propertyStream, dir));
            }
        }
    }

    /**
     * Parses a recipient directory entry which holds informations about one of possibly multiple recipients.
     * The parsed information is put into the {@link Message} object.
     *
     * @param dir The current node in the .msg file.
     * @param msg The resulting {@link Message} object.
     * @throws IOException Thrown if the .msg file could not
     *  be parsed.
     */
    protected void parseRecipient(DirectoryEntry dir, Message msg) throws IOException {
        RecipientEntry recipient = new RecipientEntry();
        DocumentEntry propertyEntry = (DocumentEntry) dir.getEntry("__properties_version1.0");
        try ( DocumentInputStream propertyStream = new DocumentInputStream(propertyEntry)) {
            propertyStream.skip(8);
            while (propertyStream.available() > 0) {
                recipient.setProperty(new Property(propertyStream, dir));
            }
        }

        msg.addRecipient(recipient);
    }

    /**
     * Creates an {@link Attachment} object based on
     * the given directory entry. The entry may either
     * point to an attached file or to an
     * attached .msg file, which will be added
     * as a {@link MsgAttachment} object instead.
     *
     * @param dir The directory entry containing the attachment
     *  document entry and some other document entries
     *  describing the attachment (name, extension, mime type, ...)
     * @param msg The {@link Message} object that this
     *  attachment should be added to.
     * @throws IOException Thrown if the attachment could
     *  not be parsed/read.
     */
    protected void parseAttachment(DirectoryEntry dir, Message msg) throws IOException {
        if (dir.hasEntry("__substg1.0_3701000D")) {
            parseEmbeddedMessage(dir, msg);
        } else {
            ParseFileAttachment(dir, msg);
        }
    }

    private void parseEmbeddedMessage(DirectoryEntry dir, Message msg) throws IOException {
        DirectoryEntry entry = (DirectoryEntry) dir.getEntry("__substg1.0_3701000D");
        Message attachmentMsg = new Message();
        MsgAttachment msgAttachment = new MsgAttachment();
        msgAttachment.setMessage(attachmentMsg);

        parseMsg(entry, attachmentMsg);

        msg.addAttachment(msgAttachment);
    }

    private void ParseFileAttachment(DirectoryEntry dir, Message msg) throws IOException {
        FileAttachment fileAttachment = new FileAttachment();
        DocumentEntry propertyEntry = (DocumentEntry) dir.getEntry("__properties_version1.0");
        try ( DocumentInputStream propertyStream = new DocumentInputStream(propertyEntry)) {
            propertyStream.skip(8);
            while (propertyStream.available() > 0) {
                Property property = new Property(propertyStream, dir);
                fileAttachment.setProperty(property.getPid(), property.getValue());
            }
        }

        msg.addAttachment(fileAttachment);
    }
}
