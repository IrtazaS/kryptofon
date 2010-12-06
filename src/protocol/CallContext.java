
package protocol;

import java.io.IOException;

import utils.Log;
import utils.OctetBuffer;

import audio.AudioInterface;

/**
 *  CallContext deals with all the packets that are part of a specific call.
 *
 *  The thing to remember is that a <em>received</em> message contains fields 
 *  with the <em>senders</em> viewpoint so <em>source</em> is the far end and 
 *  <em>dest</em> is us. in the reply the oposite is true: <em>source</em> 
 *  is us and <em>dest</em> is them.
 *  
 *  @author Mikica B Kocic
 */
public class CallContext 
{
    /** Represents the Source Call Number in the PDU header */
    private int sourceCallNumber = 1;
    
    /** Represents the Destination Call Number in the PDU header */
    private int destinationCallNumber = 0;

    /** The outbound stream sequence number */
    private int outSeqNo = 0;

    /** The inbound stream sequence number */
    private int inSeqNo = 0;
    
    /** The start time-stamp of the call */
    private long startTimestamp = 0;

    /** The remote peer; owner of the call */
    private RemotePeer remotePeer = null;
    
    /** The audio interface used to play/record for our voice PDUs */
    AudioInterface audioInterface = null;
    
    /** The flag that indicates that the call is established */
    private boolean callEstablished = false;
    
    /** Used by onReceivedVoicePDU() to stop ringing */
    private boolean receivedFirstVoicePDU = false;

    /**
     * The outbound constructor for Call. We know nothing except where to send it.
     */
    public CallContext( RemotePeer remotePeer, AudioInterface audioInterface ) 
    {
        this.audioInterface = audioInterface;
        
        synchronized( remotePeer )
        {
            this.remotePeer = remotePeer;
            this.remotePeer.addNewCall( this );
        }
    }

    /**
     *  Generates a new outbound stream sequence number.
     */
    synchronized public int getOutSeqNoInc ()
    {
        return ( this.outSeqNo++ ) & 0xFF;
    }

    /**
     *  Returns the outbound stream sequence number, oSeqno.
     */
    synchronized public int getOutSeqNo () 
    {
        return this.outSeqNo;
    }

    /**
     *  Returns the inbound stream sequence number.
     */
    public int getInSeqNo ()
    {
        return this.inSeqNo;
    }

    /**
     *  Sets the inbound stream sequence number.
     */
    public void setInSeqNo( int next ) 
    {
        this.inSeqNo = next % 256;
    }

    /**
     *  Sends a PDU to our peer.
     *
     *  @param pdu The PDU (in bytes)
     */
    public void send( OctetBuffer pdu )
    {
        if ( this.remotePeer != null ) {
            this.remotePeer.send( pdu );
        }
    }

    /**
     *  Returns the timestamp of this call. This is the number of milliseconds 
     *  since the call started.
     */
    public int getTimestamp () 
    {
        long now = System.currentTimeMillis ();
        return (int) ( now - this.startTimestamp );
    }

    /**
     *  Starts sending our audio interface recording. This method creates a new
     *  VoicePduSender object to do that.
     */
    private void startAudioRecording ()
    {
        if ( this.audioInterface == null ) {
            return;
        }

        VoicePDUSender voicePduSender = new VoicePDUSender( this.audioInterface, this );
        this.audioInterface.setAudioSender( voicePduSender );
        
        this.audioInterface.startRecording ();
    }

    /**
     *  Stops sending audio
     */
    public void stopAudioRecording ()
    {
        if ( this.audioInterface == null ) {
            return;
        }

        this.audioInterface.stopRecording ();
    }

    /**
     *  Sets if this call is established.
     *  This can either be when we receive a ANSWER PDU from our peer
     *  to an outbound call, or when we answer an incoming call ourselves.
     */
    public void setCallEstablished( boolean established )
    {
        if ( ! this.callEstablished && established )
        {
            this.audioInterface.stopRinging ();
            startAudioRecording ();
        }

        this.callEstablished = established;
    }

    /**
     *  Returns if this call has been callAnswered.
     */
    public boolean isEstablished ()
    {
        return this.callEstablished;
    }

    /**
     * Writes audio data to the speaker.
     *
     * @param bs The incoming audio payload
     * @param timestamp The time-stamp
     * @exception IOException Description of Exception
     */
    public void audioWrite( byte[] bs, long timestamp ) throws IOException 
    {
        if ( this.audioInterface != null )
        {
            this.audioInterface.writeBuffered( bs, timestamp );
        }
    }

    /**
     *  Notifies us that a Voice PDU has been received.
     */
    public void onReceivedVoicePDU( long timestamp, byte[] audioSample )
    {
        if ( ( this.audioInterface != null ) && ( ! this.receivedFirstVoicePDU ) )
        {
            /* Stop ringing audio interface
             */
            this.receivedFirstVoicePDU = true;
            this.audioInterface.stopRinging ();
        }

        /* write samples to audio interface 
         */
        try {
            audioWrite( audioSample, timestamp );
        } catch( IOException e ) {
            Log.exception( Log.WARN, e );
        }
    }

    /**
     *  Sets the local call number as a character.
     */
    public void setSourceCallNumber( int callNo )
    {
        this.sourceCallNumber = callNo;
    }

    /**
     *  Returns the local call number. Together with the remote call
     *  number, they uniquely identify the call between two parties.
     *
     *  On an outgoing call this represents 'Source Call Number', and
     *  on an incoming call this represents 'Destination Call Number'.
     */
    public int getSourceCallNumber ()
    {
        return this.sourceCallNumber & 0x7FFF;
    }

    /**
     *  Sets the remote call number as a character. This bit of information comes 
     *  in with received accept call.
     */
    public void setDestinationCallNumber( int callNo )
    {
        this.destinationCallNumber = callNo;
    }

    /**
     *  Returns the remote call number. Together with the local call
     *  number, they uniquely identify the call between two parties.
     *
     *  On an outgoing call this represents 'Destination Call Number', and
     *  on an inbound call this represents 'Source Call Number'.
     */
    public int getDestinationCallNumber ()
    {
        return this.destinationCallNumber & 0x7FFF;
    }

    /**
     *  Resets the clock. This method sets the start timestamp of a new call.
     */
    public void resetClock ()
    {
        this.startTimestamp = System.currentTimeMillis ();
    }

    /**
     *  Returns the start time-stamp of the call.
     */
    public long getStartTimestamp () 
    {
        return this.startTimestamp;
    }

    /**
     *  Passed a newly arrived PDU. If the PDU is the next one we
     *  are expecting, then put it in the buffer and adjust our
     *  expectations. Return it, so it can be acted upon. If it isn't
     *  the next expected then ignore it and return null (Warn).
     */
    public synchronized ProtocolDataUnit addIn( ProtocolDataUnit pdu )
    {
        ProtocolDataUnit ret = null;
        int where = pdu.outSeqNo;
        int expected = this.getInSeqNo ();
        if ( expected == where ) {
            setInSeqNo( ++where );
            ret = pdu;
        }
        return ret;
    }

    /**
     *  Cleans up and detaches call from peer
     */
    public void cleanUp ()
    {
        if ( this.audioInterface != null ) 
        {
            this.audioInterface.setAudioSender( null );
            this.audioInterface.stopPlay ();
            this.audioInterface.stopRecording ();
        }
        
        this.remotePeer = null;
    }

    /**
     * Returns the audio interface sample size (used to determine size of the VoicePDU).
     */
    public int getAudioSampleSize () 
    {
        if ( this.audioInterface != null ) {
            return this.audioInterface.getSampleSize ();
        }
        return 0;
    }
}
