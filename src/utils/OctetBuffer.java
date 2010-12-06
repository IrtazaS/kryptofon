
package utils;

/**
 *  Encapsulates binary payload that can be manipulated on the octet (byte) level.
 */
public class OctetBuffer
{
    /**
     *  The octet storage
     */
    private byte[] store = null;
    
    /**
     *  Position index
     */
    private int position = 0;
    
    /**
     *  Offset into backing store to enable slicing
     */
    private int sliceOffset = 0;

    /**
     *  Allocates buffer
     */
    public static OctetBuffer allocate( int size ) 
    {
        OctetBuffer bb = new OctetBuffer ();
        bb.store = new byte[ size ];
        bb.position = 0;
        bb.sliceOffset = 0;
        return bb;
    }

    /**
     *  Wraps existing byte[] array
     */
    public static OctetBuffer wrap( byte[] bs )
    {
        OctetBuffer bb = new OctetBuffer ();
        bb.store = bs;
        bb.position = 0;
        bb.sliceOffset = 0;
        return bb;
    }

    /**
     *  Slices buffer 
     */
    public OctetBuffer slice ()
    {
        OctetBuffer bb = new OctetBuffer();
        bb.store = store;
        bb.position = 0;
        bb.sliceOffset = position;
        return bb;
    }

    /**
     *  Returns internal byte[] store
     */
    public byte[] getStore ()
    {
        if ( sliceOffset != 0 ) {
            throw new java.lang.IllegalStateException ();
        }

        return store;
    }

    /**
     *  Returns internal byte[] store
     */
    public int getStoreSize ()
    {
        if ( sliceOffset != 0 ) {
            throw new java.lang.IllegalStateException ();
        }

        return store.length;
    }

    /**
     *  Gets current position
     */
    public int getPosition ()
    {
        return position;
    }

    /**
     *  Returns remaining space
     */
    public int getFreeSpace ()
    {
        return store.length - sliceOffset - position;
    }

    /**
     *  Returns true if there is free space in the store
     */
    public boolean hasFreeSpace ()
    {
        return sliceOffset + position < store.length;
    }
    //////////////////////////////////////////////////////////////// short ///////////////
    
    /**
     *  Gets primitive 'short' from the slice offset in the store
     */
    public short getShort ()
    {
        if (sliceOffset + position + 2 > store.length) {
            throw new IndexOutOfBoundsException();
        }
        short s = (short) ( (store[sliceOffset + position] << 8) +
                           (store[sliceOffset + position + 1] & 0xFF));
        position += 2;
        return s;
    }

    /**
     *  Gets primitive 'short' from the offset in the store
     */
    public short getShort( int offset )
    {
        if (sliceOffset + offset + 2 > store.length) {
            throw new IndexOutOfBoundsException();
        }
        short s = (short) ( (store[sliceOffset + offset] << 8) +
                           (store[sliceOffset + offset + 1] & 0xFF));
        return s;
    }

    /**
     *  Puts primitive 'short' at the slice offset in the store
     */
    public void putShort( short value )
    {
        if (sliceOffset + position + 2 > store.length) {
            throw new IndexOutOfBoundsException();
        }
        store[sliceOffset + position++] = (byte) (value >> 8);
        store[sliceOffset + position++] = (byte) (value & 0xff);
    }

    /**
     *  Puts primitive 'short' at the slice offset in the store
     */
    public void putShort( int offset, short value )
    {
        if (sliceOffset + offset + 2 > store.length) {
            throw new IndexOutOfBoundsException();
        }
        store[sliceOffset + offset++] = (byte) (value >> 8);
        store[sliceOffset + offset++] = (byte) (value & 0xff);
    }

    //////////////////////////////////////////////////////////////// int /////////////////
    
    /**
     *  Gets primitive 'int' from the slice offset in the store
     */
    public int getInt ()
    {
        if (sliceOffset + position + 4 > store.length) {
            throw new IndexOutOfBoundsException();
        }
        int i = (store[sliceOffset + position] << 24)
            + ( (store[sliceOffset + position + 1] & 0xFF) << 16)
            + ( (store[sliceOffset + position + 2] & 0xFF) << 8)
            + (store[sliceOffset + position + 3] & 0xFF);
        position += 4;
        return i;
    }

    /**
     *  Puts primitive 'int' at the slice offset in the store
     */
    public void putInt( int value )
    {
        if (sliceOffset + position + 4 > store.length) {
            throw new IndexOutOfBoundsException();
        }
        store[sliceOffset + position++] = (byte) (value >> 24);
        store[sliceOffset + position++] = (byte) ( (value >> 16) & 0xff);
        store[sliceOffset + position++] = (byte) ( (value >> 8) & 0xff);
        store[sliceOffset + position++] = (byte) (value & 0xff);
    }

    //////////////////////////////////////////////////////////////// byte ////////////////
    
    /**
     *  Gets primitive 'byte' from the slice offset in the store
     */
    public byte get ()
    {
        if (sliceOffset + position + 1 > store.length) {
            throw new IndexOutOfBoundsException();
        }
        return store[sliceOffset + position++];
    }

    /**
     *  Puts primitive 'byte' from the slice offset in the store
     */
    public void put( byte value )
    {
        if (sliceOffset + position + 1 > store.length) {
            throw new IndexOutOfBoundsException();
        }
        store[position] = value;
        position++;
    }

    //////////////////////////////////////////////////////////////// byte[] //////////////
    
    /**
     *  Puts byte[] array at the slice offset in the store
     */
    public void put( byte[] array ) 
    {
        if (sliceOffset + position + array.length > store.length) {
            throw new IndexOutOfBoundsException();
        }
        System.arraycopy(array, 0, store, sliceOffset + position, array.length);
        position += array.length;
    }

    /**
     *  Gets byte[] array from the slice offset in the store
     */
    public void get( byte[] array )
    {
        int l = getFreeSpace();
        if (l > array.length) {
            l = array.length;
        }
        System.arraycopy(store, sliceOffset + position, array, 0, l);
        position += l;
    }

    //////////////////////////////////////////////////////////////// char ////////////////
    
    /**
     *  Puts primitive 'char' at the slice offset in the store
     */
    public void putChar( char value )
    {
        if (sliceOffset + position + 2 > store.length) {
            throw new IndexOutOfBoundsException();
        }
        store[sliceOffset + position++] = (byte) ( ( (short) value) >> 8);
        store[sliceOffset + position++] = (byte) value;
    }

    /**
     *  Puts primitive 'char' at the slice offset in the store
     */
    public void putChar( int offset, char value )
    {
        if (sliceOffset + offset + 2 > store.length) {
            throw new IndexOutOfBoundsException();
        }
        store[sliceOffset + offset] = (byte) ( ( (short) value) >> 8);
        store[sliceOffset + offset + 1] = (byte) value;
    }

    /**
     *  Gets primitive 'char' from the slice offset in the store
     */
    public char getChar ()
    {
        if (sliceOffset + position + 2 > store.length) {
            throw new IndexOutOfBoundsException();
        }
        short s = (short) ( (store[sliceOffset + position] << 8) +
                           (store[sliceOffset + position + 1] & 0xFF));
        position += 2;
        return (char) s;
    }

    /**
     *  Gets primitive 'char' from the slice offset in the store
     */
    public char getChar( int offset )
    {
        if (sliceOffset + offset + 2 > store.length) {
            throw new IndexOutOfBoundsException();
        }
        short s = (short) ( (store[sliceOffset + offset] << 8) +
                           (store[sliceOffset + offset + 1] & 0xFF));
        return (char) s;
    }
    
    /**
     * Converts a 'byte' to an 'int'.
     */
    public static int toInt( byte b )
    {
        return ( b + 0x100 ) & 0xFF;
    }
}
