package simpledb;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * HeapFile is an implementation of a DbFile that stores a collection of tuples
 * in no particular order. Tuples are stored on pages, each of which is a fixed
 * size, and the file is simply a collection of those pages. HeapFile works
 * closely with HeapPage. The format of HeapPages is described in the HeapPage
 * constructor.
 * 
 * @see simpledb.HeapPage#HeapPage
 * @author Sam Madden
 */
public class HeapFile implements DbFile {
    File f;
    RandomAccessFile rf;
    TupleDesc tupleDesc;

    AtomicInteger version = new AtomicInteger(0);
    //AtomicInteger numPages = new AtomicInteger();

    //AtomicInteger numPages = new AtomicInteger();
    int numPages;
    ReentrantReadWriteLock.ReadLock rLock;
    ReentrantReadWriteLock.WriteLock wLock;


    /**
     * Constructs a heap file backed by the specified file.
     * 
     * @param f
     *            the file that stores the on-disk backing store for this heap
     *            file.
     */
    public HeapFile(File f, TupleDesc td) {
        this.f = f;
        try {
            rf = new RandomAccessFile(f, "rw");
        } catch (FileNotFoundException e) {
            System.out.println("Failed at create HeapFile!");
            //System.exit(-1);
        }

        tupleDesc = td;

        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        rLock = lock.readLock();
        wLock = lock.writeLock();

        int len = (int) f.length();
        int pageSize = BufferPool.getPageSize();
        numPages = (len / pageSize) + (len % pageSize != 0 ? 1 : 0);
    }

    /**
     * Returns the File backing this HeapFile on disk.
     * 
     * @return the File backing this HeapFile on disk.
     */
    public File getFile() {
        return f;
    }

    /**
     * Returns an ID uniquely identifying this HeapFile. Implementation note:
     * you will need to generate this tableid somewhere to ensure that each
     * HeapFile has a "unique id," and that you always return the same value for
     * a particular HeapFile. We suggest hashing the absolute file name of the
     * file underlying the heapfile, i.e. f.getAbsoluteFile().hashCode().
     * 
     * @return an ID uniquely identifying this HeapFile.
     */
    public int getId() {
        return f.getAbsoluteFile().hashCode();
    }

    public int getVersion() {
        return version.get();
    }

    /**
     * Returns the TupleDesc of the table stored in this DbFile.
     * 
     * @return TupleDesc of this DbFile.
     */
    public TupleDesc getTupleDesc() {
        return tupleDesc;
    }

    // see DbFile.java for javadocs
    public Page readPage(PageId pid) {
        int pageNo = pid.getPageNumber();

        rLock.lock();
        if (pageNo < 0 || pageNo >= numPages) {
            wLock.unlock();
            throw new IllegalArgumentException();
        }
        rLock.unlock();

        /// never decrease size now...
        try {
            int pageSize = BufferPool.getPageSize();
            byte[] data = new byte[pageSize];
            rf.seek(pageNo * pageSize);
            rf.read(data);
            return new HeapPage((HeapPageId) pid, data);
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalArgumentException();
        }
    }

    // see DbFile.java for javadocs
    public void writePage(Page page) throws IOException {
        /// set pos
        int pageSize = BufferPool.getPageSize();
        HeapPage heapPage = (HeapPage) page;
        int startPos = heapPage.getId().getPageNumber() * pageSize;
        rf.seek(startPos);

        byte[] header = heapPage.getHeader();
        rf.write(header);

        /// transfer tuples into bytes...
        ByteArrayOutputStream baos = new ByteArrayOutputStream(pageSize - header.length);
        DataOutputStream dos = new DataOutputStream(baos);
        writeNextTuple(dos, heapPage.getTuples(), tupleDesc);
        rf.write(baos.toByteArray());
    }

    private void writeNextTuple(DataOutputStream dos, Tuple[] tuples, TupleDesc tupleDesc) throws IOException {
        Type[] types = tupleDesc.getTypes();
        int tupleLength = tupleDesc.getSize();
        byte[] emptyTuple = new byte[tupleLength];
        for (Tuple t : tuples) {
            if (t == null) {
                dos.write(emptyTuple);
                continue;
            }
            for (int i = 0; i < types.length; i++) {
                Type type = types[i];
                Field of = t.getField(i);
                if (type == Type.STRING_TYPE)
                    dos.write(type.toBytes(((StringField) of).getValue()));
                else
                    dos.write(type.toBytes(((IntField) of).getValue()));
            }
        }
        dos.flush();
    }

    /**
     * Returns the number of pages in this HeapFile.
     */
    public int numPages() {
        rLock.lock();
        int n = numPages;
        rLock.unlock();
        return n;
    }

    // see DbFile.java for javadocs
    public ArrayList<Page> insertTuple(TransactionId tid, Tuple t)
            throws DbException, IOException, TransactionAbortedException {
        if (!t.getTupleDesc().equals(tupleDesc))
            throw new DbException("insertTuple: tupleDesc mismatch!");

        int i;
        int tableId = getId();
        ArrayList<Page> pages = new ArrayList<>();
        BufferPool bufferPool = Database.getBufferPool();
        for (i = 0; i < numPages(); i++) {
            HeapPageId pageId = new HeapPageId(tableId, i);
            HeapPage page = (HeapPage) bufferPool.getPage(tid, pageId, Permissions.READ_WRITE);
            try {
                page.insertTuple(t);
            } catch (DbException e) {
                bufferPool.releasePage(tid, pageId);
                continue;
            }

            //page.markDirty(true, tid);
            pages.add(page);
            version.getAndIncrement();
            return pages;
        }

        wLock.lock();
        numPages++;
        rf.setLength((numPages+1) * BufferPool.getPageSize());
        wLock.unlock();
        return insertTuple(tid, t);
    }

    // see DbFile.java for javadocs
    public ArrayList<Page> deleteTuple(TransactionId tid, Tuple t) throws DbException,
            TransactionAbortedException {

        if (!t.getTupleDesc().equals(tupleDesc))
            throw new DbException("deleteTuple: tupleDesc mismatch!");

        BufferPool bufferPool = Database.getBufferPool();
        PageId pageId = t.getRecordId().getPageId();
        HeapPage page = (HeapPage) bufferPool.getPage(tid, pageId, Permissions.READ_WRITE);
        page.deleteTuple(t);
        version.getAndIncrement();
        return new ArrayList<Page>() {{ add(page); }};
    }

    // see DbFile.java for javadocs
    public DbFileIterator iterator(TransactionId tid) {
        return HeapFileScanIterator.newHeapFileScanIterator(tid, this);
    }

}

class HeapFileScanIterator extends AbstractDbFileIterator {
    HeapFile hf;
    int currPgNo;
    TransactionId tid;
    Iterator<Tuple> tupleIterator;

    static HeapFileScanIterator newHeapFileScanIterator(TransactionId tid, HeapFile file)
    {
        HeapFileScanIterator iterator = new HeapFileScanIterator();
        iterator.hf = file;
        iterator.tid = tid;
        iterator.currPgNo = -1;
        iterator.tupleIterator = null;
        return iterator;
    }

    @Override
    public Tuple readNext() throws DbException, TransactionAbortedException {
        if (currPgNo == -1) // not yet opened
            return null;
        if (tupleIterator == null) // has closed
            throw new NoSuchElementException();
        if (tupleIterator.hasNext())
            return tupleIterator.next();
        if (++currPgNo >= hf.numPages())
            return null;
        HeapPageId pageId = new HeapPageId(hf.getId(), currPgNo);
        BufferPool bufferPool = Database.getBufferPool();
        HeapPage page = (HeapPage) bufferPool.getPage(tid, pageId, Permissions.READ_ONLY);
        tupleIterator = page.iterator();
        if (tupleIterator.hasNext())
            return tupleIterator.next();
        return null;
    }

    @Override
    public void open() throws DbException, TransactionAbortedException {
        currPgNo = 0;
        HeapPageId pageId = new HeapPageId(hf.getId(), 0);
        HeapPage page = (HeapPage) Database.getBufferPool().getPage(tid, pageId, Permissions.READ_ONLY);
        tupleIterator = page.iterator();
    }

    @Override
    public void rewind() throws DbException, TransactionAbortedException {
        open();
    }

    @Override
    public void close() {
        super.close();
        tupleIterator = null;
    }
}
