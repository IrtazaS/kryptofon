
package audio;

import java.io.IOException;

/**
 *  Base class for CODECs that can convert to and from PCM.
 *  The class wraps underlying AudioInterfacePCM that can find and talk to PCM hardware.
 *  Derived classes should only implement encoding/decoding part from/to PCM
 *  (methods AbstractCODEC.convertFromPCM() and AbstractCODEC.convertToPCM()).
 */
public abstract class AbstractCODEC implements AudioInterface
{
    /**
     *  Encodes data to PCM, i.e. converts samples from PCM to CODEC format.
     */
    public abstract void convertFromPCM( byte[] in, byte[] out );

    /**
     *  Decodes data from PCM, i.e. converts samples from CODEC to PCM format.
     */
    public abstract void convertToPCM( byte[] in, byte[] out );

    /**
     * Gets the VoicePDU subclass attribute of the AbstractAudio object
     */
    public abstract int getVoicePduSubclass ();

    /**
     *  Instance of the audio interface that provides access to 
     *  PCM (signed 16-bit linear) samples.
     */
    protected AudioInterfacePCM audio;

    /**
     *  Output PCM buffer (converted from CODEC) written to audio interface
     */
    protected byte[] outputPcmBuf;

    /**
     *  Input PCM buffer (converted to CODEC) read from audio interface
     */
    protected byte[] inputPcmBuf;

    /**
     *  Stops the recorder - but don't throw it away.
     */
    public void stopRecording () 
    {
        audio.stopRecording ();
    }

    /**
     *  Starts the recorder (returning the time)
     */
    public long startRecording () 
    {
        return audio.startRecording ();
    }

    /**
     *  Starts the player
     */
    public void startPlay ()
    {
        audio.startPlay();
    }

    /**
     *  Stops the player
     */
    public void stopPlay ()
    {
        audio.stopPlay();
    }

    /**
     *  Starts ringing signal
     */
    public void startRinging ()
    {
        audio.startRinging ();
    }

    /**
     *  Stops ringing signal
     */
    public void stopRinging ()
    {
        audio.stopRinging ();
    }

    /**
     *  Plays the sample given (AudioInterface.getSampleSize() bytes) assuming 
     *  that it's timestamp is long
     */
    public void writeBuffered( byte[] buf, long timestamp ) throws IOException
    {
        convertToPCM( buf, outputPcmBuf );
        audio.writeBuffered( outputPcmBuf, timestamp );
    }

    /**
     *  Reads from the microphone, using the buffer provided,
     *  but <em>only</em> filling getSampSize() bytes.
     *  Returns the time-stamp of the sample from the audio clock.
     */
    public long readWithTimestamp( byte[] buf ) throws IOException 
    {
        long ret = audio.readWithTimestamp( inputPcmBuf );
        convertFromPCM( inputPcmBuf, buf );
        return ret;
    }

    /**
     *  Writes directly to source line without buffering
     */
    public void writeDirectly( byte[] f )
    {
        byte[] tf = new byte[ 2 * f.length ];
        convertToPCM( f, tf );
        audio.writeDirectly( tf );
    }

    /**
     * Sets the audioSender attribute of the AbstractAudio object
     */
    public void setAudioSender( AudioInterface.Packetizer as )
    {
        audio.setAudioSender( as );
    }

    /**
     *  Cleans up resources used by the interface.
     */
    public void cleanUp ()
    {
        audio.cleanUp ();
    }

    /**
     *  Creates new instance of the interface by choosing specified CODEC format
     */
    public AudioInterface getByFormat( Integer format )
    {
        return audio.getByFormat(format);
    }
}
