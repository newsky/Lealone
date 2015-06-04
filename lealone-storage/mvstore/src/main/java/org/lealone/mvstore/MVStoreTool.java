/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.mvstore;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import org.lealone.engine.Constants;
import org.lealone.fs.FilePath;
import org.lealone.fs.FileUtils;
import org.lealone.message.DbException;
import org.lealone.type.DataType;
import org.lealone.type.StringDataType;
import org.lealone.type.WriteBuffer;
import org.lealone.util.DataUtils;

/**
 * Utility methods used in combination with the MVStore.
 */
public class MVStoreTool {

    /**
     * Runs this tool.
     * Options are case sensitive. Supported options are:
     * <table>
     * <tr><td>[-dump &lt;fileName&gt;]</td>
     * <td>Dump the contends of the file</td></tr>
     * <tr><td>[-info &lt;fileName&gt;]</td>
     * <td>Get summary information about a file</td></tr>
     * <tr><td>[-compact &lt;fileName&gt;]</td>
     * <td>Compact a store</td></tr>
     * <tr><td>[-compress &lt;fileName&gt;]</td>
     * <td>Compact a store with compression enabled</td></tr>
     * </table>
     *
     * @param args the command line arguments
     */
    public static void main(String... args) {
        for (int i = 0; i < args.length; i++) {
            if ("-dump".equals(args[i])) {
                String fileName = args[++i];
                dump(fileName, new PrintWriter(System.out), true);
            } else if ("-info".equals(args[i])) {
                String fileName = args[++i];
                info(fileName, new PrintWriter(System.out));
            } else if ("-compact".equals(args[i])) {
                String fileName = args[++i];
                compact(fileName, false);
            } else if ("-compress".equals(args[i])) {
                String fileName = args[++i];
                compact(fileName, true);
            }
        }
    }

    /**
     * Read the contents of the file and write them to system out.
     *
     * @param fileName the name of the file
     * @param details whether to print details
     */
    public static void dump(String fileName, boolean details) {
        dump(fileName, new PrintWriter(System.out), details);
    }

    /**
     * Read the summary information of the file and write them to system out.
     *
     * @param fileName the name of the file
     */
    public static void info(String fileName) {
        info(fileName, new PrintWriter(System.out));
    }

    /**
     * Read the contents of the file and display them in a human-readable
     * format.
     *
     * @param fileName the name of the file
     * @param writer the print writer
     * @param details print the page details
     */
    public static void dump(String fileName, Writer writer, boolean details) {
        PrintWriter pw = new PrintWriter(writer, true);
        if (!FilePath.get(fileName).exists()) {
            pw.println("File not found: " + fileName);
            return;
        }
        long size = FileUtils.size(fileName);
        pw.printf("File %s, %d bytes, %d MB\n", fileName, size, size / 1024 / 1024);
        FileChannel file = null;
        int blockSize = MVStore.BLOCK_SIZE;
        TreeMap<Integer, Long> mapSizesTotal = new TreeMap<Integer, Long>();
        long pageSizeTotal = 0;
        try {
            file = FilePath.get(fileName).open("r");
            long fileSize = file.size();
            int len = Long.toHexString(fileSize).length();
            ByteBuffer block = ByteBuffer.allocate(4096);
            long pageCount = 0;
            for (long pos = 0; pos < fileSize;) {
                block.rewind();
                DataUtils.readFully(file, pos, block);
                block.rewind();
                int headerType = block.get();
                if (headerType == 'H') {
                    pw.printf("%0" + len + "x fileHeader %s%n", pos, new String(block.array(), DataUtils.LATIN).trim());
                    pos += blockSize;
                    continue;
                }
                if (headerType != 'c') {
                    pos += blockSize;
                    continue;
                }
                block.position(0);
                Chunk c = null;
                try {
                    c = Chunk.readChunkHeader(block, pos);
                } catch (IllegalStateException e) {
                    pos += blockSize;
                    continue;
                }
                if (c.len <= 0) {
                    // not a chunk
                    pos += blockSize;
                    continue;
                }
                int length = c.len * MVStore.BLOCK_SIZE;
                pw.printf("%n%0" + len + "x chunkHeader %s%n", pos, c.toString());
                ByteBuffer chunk = ByteBuffer.allocate(length);
                DataUtils.readFully(file, pos, chunk);
                int p = block.position();
                pos += length;
                int remaining = c.pageCount;
                pageCount += c.pageCount;
                TreeMap<Integer, Integer> mapSizes = new TreeMap<Integer, Integer>();
                int pageSizeSum = 0;
                while (remaining > 0) {
                    try {
                        chunk.position(p);
                    } catch (IllegalArgumentException e) {
                        // too far
                        pw.printf("ERROR illegal position %d%n", p);
                        break;
                    }
                    int pageSize = chunk.getInt();
                    // check value (ignored)
                    chunk.getShort();
                    int mapId = DataUtils.readVarInt(chunk);
                    int entries = DataUtils.readVarInt(chunk);
                    int type = chunk.get();
                    boolean compressed = (type & 2) != 0;
                    boolean node = (type & 1) != 0;
                    if (details) {
                        pw.printf("+%0" + len + "x %s, map %x, %d entries, %d bytes, maxLen %x%n", p, (node ? "node"
                                : "leaf") + (compressed ? " compressed" : ""), mapId, node ? entries + 1 : entries,
                                pageSize, DataUtils.getPageMaxLength(DataUtils.getPagePos(0, 0, pageSize, 0)));
                    }
                    p += pageSize;
                    Integer mapSize = mapSizes.get(mapId);
                    if (mapSize == null) {
                        mapSize = 0;
                    }
                    mapSizes.put(mapId, mapSize + pageSize);
                    Long total = mapSizesTotal.get(mapId);
                    if (total == null) {
                        total = 0L;
                    }
                    mapSizesTotal.put(mapId, total + pageSize);
                    pageSizeSum += pageSize;
                    pageSizeTotal += pageSize;
                    remaining--;
                    long[] children = null;
                    long[] counts = null;
                    if (node) {
                        children = new long[entries + 1];
                        for (int i = 0; i <= entries; i++) {
                            children[i] = chunk.getLong();
                        }
                        counts = new long[entries + 1];
                        for (int i = 0; i <= entries; i++) {
                            long s = DataUtils.readVarLong(chunk);
                            counts[i] = s;
                        }
                    }
                    String[] keys = new String[entries];
                    if (mapId == 0 && details) {
                        if (!compressed) {
                            for (int i = 0; i < entries; i++) {
                                String k = StringDataType.INSTANCE.read(chunk);
                                keys[i] = k;
                            }
                        }
                        if (node) {
                            // meta map node
                            for (int i = 0; i < entries; i++) {
                                long cp = children[i];
                                pw.printf("    %d children < %s @ " + "chunk %x +%0" + len + "x%n", counts[i], keys[i],
                                        DataUtils.getPageChunkId(cp), DataUtils.getPageOffset(cp));
                            }
                            long cp = children[entries];
                            pw.printf("    %d children >= %s @ chunk %x +%0" + len + "x%n", counts[entries],
                                    keys.length >= entries ? null : keys[entries], DataUtils.getPageChunkId(cp),
                                    DataUtils.getPageOffset(cp));
                        } else if (!compressed) {
                            // meta map leaf
                            String[] values = new String[entries];
                            for (int i = 0; i < entries; i++) {
                                String v = StringDataType.INSTANCE.read(chunk);
                                values[i] = v;
                            }
                            for (int i = 0; i < entries; i++) {
                                pw.println("    " + keys[i] + " = " + values[i]);
                            }
                        }
                    } else {
                        if (node && details) {
                            for (int i = 0; i <= entries; i++) {
                                long cp = children[i];
                                pw.printf("    %d children @ chunk %x +%0" + len + "x%n", counts[i],
                                        DataUtils.getPageChunkId(cp), DataUtils.getPageOffset(cp));
                            }
                        }
                    }
                }
                pageSizeSum = Math.max(1, pageSizeSum);
                for (Integer mapId : mapSizes.keySet()) {
                    int percent = 100 * mapSizes.get(mapId) / pageSizeSum;
                    pw.printf("map %x: %d bytes, %d%%%n", mapId, mapSizes.get(mapId), percent);
                }
                int footerPos = chunk.limit() - Chunk.FOOTER_LENGTH;
                try {
                    chunk.position(footerPos);
                    pw.printf("+%0" + len + "x chunkFooter %s%n", footerPos, new String(chunk.array(),
                            chunk.position(), Chunk.FOOTER_LENGTH, DataUtils.LATIN).trim());
                } catch (IllegalArgumentException e) {
                    // too far
                    pw.printf("ERROR illegal footer position %d%n", footerPos);
                }
            }
            pw.printf("%n%0" + len + "x eof%n", fileSize);
            pw.printf("\n");
            pageCount = Math.max(1, pageCount);
            pw.printf("page size total: %d bytes, page count: %d, average page size: %d bytes\n", pageSizeTotal,
                    pageCount, pageSizeTotal / pageCount);
            pageSizeTotal = Math.max(1, pageSizeTotal);
            for (Integer mapId : mapSizesTotal.keySet()) {
                int percent = (int) (100 * mapSizesTotal.get(mapId) / pageSizeTotal);
                pw.printf("map %x: %d bytes, %d%%%n", mapId, mapSizesTotal.get(mapId), percent);
            }
        } catch (IOException e) {
            pw.println("ERROR: " + e);
            e.printStackTrace(pw);
        } finally {
            if (file != null) {
                try {
                    file.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
        pw.flush();
    }

    /**
     * Read the summary information of the file and write them to system out.
     *
     * @param fileName the name of the file
     * @param writer the print writer
     */
    public static void info(String fileName, Writer writer) {
        PrintWriter pw = new PrintWriter(writer, true);
        if (!FilePath.get(fileName).exists()) {
            pw.println("File not found: " + fileName);
            return;
        }
        long fileLength = FileUtils.size(fileName);
        MVStore store = new MVStore.Builder().fileName(fileName).readOnly().open();
        try {
            MVMap<String, String> meta = store.getMetaMap();
            Map<String, Object> header = store.getStoreHeader();
            long fileCreated = DataUtils.readHexLong(header, "created", 0L);
            TreeMap<Integer, Chunk> chunks = new TreeMap<Integer, Chunk>();
            long chunkLength = 0;
            long maxLength = 0;
            long maxLengthLive = 0;
            long maxLengthNotEmpty = 0;
            for (Entry<String, String> e : meta.entrySet()) {
                String k = e.getKey();
                if (k.startsWith("chunk.")) {
                    Chunk c = Chunk.fromString(e.getValue());
                    chunks.put(c.id, c);
                    chunkLength += c.len * MVStore.BLOCK_SIZE;
                    maxLength += c.maxLen;
                    maxLengthLive += c.maxLenLive;
                    if (c.maxLenLive > 0) {
                        maxLengthNotEmpty += c.maxLen;
                    }
                }
            }
            pw.printf("Created: %s\n", formatTimestamp(fileCreated, fileCreated));
            pw.printf("Last modified: %s\n", formatTimestamp(FileUtils.lastModified(fileName), fileCreated));
            pw.printf("File length: %d\n", fileLength);
            pw.printf("The last chunk is not listed\n");
            pw.printf("Chunk length: %d\n", chunkLength);
            pw.printf("Chunk count: %d\n", chunks.size());
            pw.printf("Used space: %d%%\n", getPercent(chunkLength, fileLength));
            pw.printf("Chunk fill rate: %d%%\n", maxLength == 0 ? 100 : getPercent(maxLengthLive, maxLength));
            pw.printf("Chunk fill rate excluding empty chunks: %d%%\n",
                    maxLengthNotEmpty == 0 ? 100 : getPercent(maxLengthLive, maxLengthNotEmpty));
            for (Entry<Integer, Chunk> e : chunks.entrySet()) {
                Chunk c = e.getValue();
                long created = fileCreated + c.time;
                pw.printf("  Chunk %d: %s, %d%% used, %d blocks", c.id, formatTimestamp(created, fileCreated),
                        getPercent(c.maxLenLive, c.maxLen), c.len);
                if (c.maxLenLive == 0) {
                    pw.printf(", unused: %s", formatTimestamp(fileCreated + c.unused, fileCreated));
                }
                pw.printf("\n");
            }
            pw.printf("\n");
        } catch (Exception e) {
            pw.println("ERROR: " + e);
            e.printStackTrace(pw);
        } finally {
            store.close();
        }
        pw.flush();
    }

    private static String formatTimestamp(long t, long start) {
        String x = new Timestamp(t).toString();
        String s = x.substring(0, 19);
        s += " (+" + ((t - start) / 1000) + " s)";
        return s;
    }

    private static int getPercent(long value, long max) {
        if (value == 0) {
            return 0;
        } else if (value == max) {
            return 100;
        }
        return (int) (1 + 98 * value / Math.max(1, max));
    }

    /**
     * Compress the store by creating a new file and copying the live pages
     * there. Temporarily, a file with the suffix ".tempFile" is created. This
     * file is then renamed, replacing the original file, if possible. If not,
     * the new file is renamed to ".newFile", then the old file is removed, and
     * the new file is renamed. This might be interrupted, so it's better to
     * compactCleanUp before opening a store, in case this method was used.
     *
     * @param fileName the file name
     * @param compress whether to compress the data
     */
    public static void compact(String fileName, boolean compress) {
        String tempName = fileName + Constants.SUFFIX_MV_STORE_TEMP_FILE;
        FileUtils.delete(tempName);
        compact(fileName, tempName, compress);
        try {
            FileUtils.moveAtomicReplace(tempName, fileName);
        } catch (DbException e) {
            String newName = fileName + Constants.SUFFIX_MV_STORE_NEW_FILE;
            FileUtils.delete(newName);
            FileUtils.move(tempName, newName);
            FileUtils.delete(fileName);
            FileUtils.move(newName, fileName);
        }
    }

    /**
     * Clean up if needed, in a case a compact operation was interrupted due to
     * killing the process or a power failure. This will delete temporary files
     * (if any), and in case atomic file replacements were not used, rename the
     * new file.
     *
     * @param fileName the file name
     */
    public static void compactCleanUp(String fileName) {
        String tempName = fileName + Constants.SUFFIX_MV_STORE_TEMP_FILE;
        if (FileUtils.exists(tempName)) {
            FileUtils.delete(tempName);
        }
        String newName = fileName + Constants.SUFFIX_MV_STORE_NEW_FILE;
        if (FileUtils.exists(newName)) {
            if (FileUtils.exists(fileName)) {
                FileUtils.delete(newName);
            } else {
                FileUtils.move(newName, fileName);
            }
        }
    }

    /**
     * Copy all live pages from the source store to the target store.
     *
     * @param sourceFileName the name of the source store
     * @param targetFileName the name of the target store
     * @param compress whether to compress the data
     */
    public static void compact(String sourceFileName, String targetFileName, boolean compress) {
        MVStore source = new MVStore.Builder().fileName(sourceFileName).readOnly().open();
        FileUtils.delete(targetFileName);
        MVStore.Builder b = new MVStore.Builder().fileName(targetFileName);
        if (compress) {
            b.compress();
        }
        MVStore target = b.open();
        compact(source, target);
        target.close();
        source.close();
    }

    /**
     * Copy all live pages from the source store to the target store.
     *
     * @param source the source store
     * @param target the target store
     */
    public static void compact(MVStore source, MVStore target) {
        MVMap<String, String> sourceMeta = source.getMetaMap();
        MVMap<String, String> targetMeta = target.getMetaMap();
        for (Entry<String, String> m : sourceMeta.entrySet()) {
            String key = m.getKey();
            if (key.startsWith("chunk.")) {
                // ignore
            } else if (key.startsWith("map.")) {
                // ignore
            } else if (key.startsWith("name.")) {
                // ignore
            } else if (key.startsWith("root.")) {
                // ignore
            } else {
                targetMeta.put(key, m.getValue());
            }
        }
        for (String mapName : source.getMapNames()) {
            MVMap.Builder<Object, Object> mp = new MVMap.Builder<Object, Object>().keyType(new GenericDataType())
                    .valueType(new GenericDataType());
            MVMap<Object, Object> sourceMap = source.openMap(mapName, mp);
            MVMap<Object, Object> targetMap = target.openMap(mapName, mp);
            targetMap.copyFrom(sourceMap);
        }
    }

    /**
     * A data type that can read any data that is persisted, and converts it to
     * a byte array.
     */
    static class GenericDataType implements DataType {

        @Override
        public int compare(Object a, Object b) {
            throw DataUtils.newUnsupportedOperationException("Can not compare");
        }

        @Override
        public int getMemory(Object obj) {
            return obj == null ? 0 : ((byte[]) obj).length * 8;
        }

        @Override
        public void write(WriteBuffer buff, Object obj) {
            if (obj != null) {
                buff.put((byte[]) obj);
            }
        }

        @Override
        public void write(WriteBuffer buff, Object[] obj, int len, boolean key) {
            for (Object o : obj) {
                write(buff, o);
            }
        }

        @Override
        public Object read(ByteBuffer buff) {
            int len = buff.remaining();
            if (len == 0) {
                return null;
            }
            byte[] data = new byte[len];
            buff.get(data);
            return data;
        }

        @Override
        public void read(ByteBuffer buff, Object[] obj, int len, boolean key) {
            for (int i = 0; i < obj.length; i++) {
                obj[i] = read(buff);
            }
        }

    }

}
