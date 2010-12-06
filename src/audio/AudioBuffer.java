
package audio;

/**
 *  Encapsulates the concept of an audio buffer with a time-stamp.
 *  It also contains 'written' flag, which can be used to indicate
 *  whether there is written but unread data in the buffer.
 */
public class AudioBuffer
{
    private byte[] buf;
    private boolean written;
    private long timestamp;

    /**
     *  Constructs buffer with given size.
     */
    public AudioBuffer( int size )
    {
        this.buf = new byte[ size ];
    }
    
    /**
     *  Gets buffer contents.
     */
    public byte[] getByteArray ()
    {
        return this.buf;
    }
    
    /**
     *  Returns status of the flag 'written'
     */
    public boolean isWritten () 
    {
        return this.written;
    }
    
    /**
     *  Sets the flag 'written'
     */
    public void setWritten () 
    {
        this.written = true;
    }
    
    /**
     *  Resets the flag 'written'
     */
    public void setRead () 
    {
        this.written = false;
    }
    
    /**
     *  Returns the time-stamp associated with the buffer
     */
    public long getTimestamp () 
    {
        return timestamp;
    }
    
    /**
     *  Associates time-stamp with the buffer.
     */
    public void setTimestamp( long timestamp ) 
    {
        this.timestamp = timestamp;
    }
}
