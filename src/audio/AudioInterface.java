
/**
 *  Audio interfaces and CODECs
 */
package audio;

import java.io.IOException;

/**
 *  The abstract audio interface.
 *
 *  @author Mikica B Kocic
 */
public interface AudioInterface
{
    /**
     *  Audio sender interface used by instance of the AudioInterface class 
     *  to send captured audio samples to the remote peer.
     */
    public interface Packetizer 
    {
        public abstract void send () throws IOException;
    }

    /**
     *  Returns the minimum sample size for use in creating buffers etc.
     */
    public abstract int getSampleSize ();
    
    /**
     *  Reads from the microphone, using the buffer provided,
     *  but <em>only</em> filling getSampSize() bytes.
     *  Returns the time-stamp of the sample from the audio clock.
     */
    public abstract long readWithTimestamp( byte[] buff ) throws IOException;
    
    /**
     *  Stops the recorder - but don't throw it away.
     */
    public abstract void stopRecording ();
    
    /**
     *  Starts the recorder (returning the time-stamp)
     */
    public abstract long startRecording ();
    
    /**
     *  Starts the player
     */
    public abstract void startPlay ();
    
    /**
     *  Stops the player
     */
    public abstract void stopPlay ();

    /**
     *  Starts ringing signal
     */
    public abstract void startRinging ();
    
    /**
     *  Stops ringing signal
     */
    public abstract void stopRinging();
    
    /**
     *  Plays the sample given (AudioInterface.getSampleSize() bytes) assuming 
     *  that it's timestamp is long
     */
    public abstract void writeBuffered( byte[] buff, long timestamp ) throws IOException;
    
    /**
     *  Writes directly to source line without buffering
     */
    public abstract void writeDirectly( byte[] buff ) throws IOException;
    
    /**
     *  Gets the VoicePDU subclass attribute of the AbstractAudio object
     */
    public abstract int getVoicePduSubclass();
    
    /**
     *  Sets the audioSender attribute of the AbstractAudio object
     */
    public abstract void setAudioSender( AudioInterface.Packetizer as );
    
    /**
     *  Cleans up resources used by the interface.
     */
    public abstract void cleanUp ();
    
    /**
     *  Creates new instance of the interface by choosing specified CODEC format
     */
    public abstract AudioInterface getByFormat( Integer format );
}
