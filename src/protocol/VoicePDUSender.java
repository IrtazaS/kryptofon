
package protocol;

import java.io.IOException;

import utils.Log;

import audio.AudioInterface;

/**
 *  Takes captured audio and sends it to the remote peer via UDP channel
 *  
 *  @author Mikica B Kocic
 */
public class VoicePDUSender implements AudioInterface.Packetizer
{
    private AudioInterface audio;
    private CallContext call;
    
    private int voicePduSubclass;
    private byte[] audioBuffer;
    private long callStartTimestamp;
    private long nextDueTimestamp;
    private int timestamp;

    /**
     * Constructor for the VoicePDUSender object
     *
     * @param audioInterface The audio interface
     * @param call The call object
     */
    VoicePDUSender( AudioInterface audioInterface, CallContext call )
    {
        this.audio = audioInterface;
        this.call = call;
        
        this.voicePduSubclass = audioInterface.getVoicePduSubclass ();
        this.audioBuffer = new byte[ this.audio.getSampleSize () ];

        this.callStartTimestamp = this.call.getTimestamp ();
        this.nextDueTimestamp = this.callStartTimestamp;
    }

    /**
     *  Sends audio as payload encapsulated in VoicePDU
     */
    public void send () throws IOException
    {
        this.audio.readWithTimestamp( this.audioBuffer );
        this.timestamp = (int) this.nextDueTimestamp;
        
        VoicePDU vf = new VoicePDU( this.call, this.voicePduSubclass );
        vf.setTimestamp( this.timestamp );
        vf.sendPayload( this.audioBuffer );
        
        vf.dump( "Outbound Voice" );
        Log.audio( "Sent voice PDU" );
        
        /* Now work out how long to wait...
         */
        this.nextDueTimestamp += 20;
    }

}
