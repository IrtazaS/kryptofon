
package protocol;

/**
 *  VoicePDU - The PDU that carries voice payload.
 *  
 *  @author Mikica B Kocic  
 */
public class VoicePDU extends ProtocolDataUnit
{
    /**
     *  Voice PDU Subclass: 16-bit linear little-endian
     */
    public final static int LIN16 = 0x01;

    /**
     *  Voice PDU Subclass: G.711 a-Law
     */
    public final static int ALAW = 0x02;

    /**
     *  Voice PDU Subclass: G.711 u-Law
     */
    public final static int ULAW = 0x03;

    /**
     *  The constructor for outbound Voice PDUs.
     */
    public VoicePDU( CallContext c, int subClass )
    {
        super( c );
        this.pduType = ProtocolDataUnit.VOICE;
        this.pduSubclass = subClass;
    }

    /**
     *  The constructor for inbound Voice PDUs.
     */
    public VoicePDU( CallContext c, byte[] pduOctets )
    {
        super( c, pduOctets );
    }

    /**
     *  Logs this frame.
     */
    protected void log( String prefix ) 
    {
        super.log( prefix + " voice frame" );
    }

    /**
     *  Handles arrived PDUs: writes those to audio buffer.
     */
    @Override
    void onArrivedPDU () 
    {
        dump( "Inbound Voice" );
        
        int audioSampleSize = this.call.getAudioSampleSize ();
        
        byte[] audioSample = new byte[ audioSampleSize ];
        payload.get( audioSample );

        long ts = this.getTimestamp ();
        
        if ( this.call != null ) {
            this.call.onReceivedVoicePDU( ts, audioSample );
        }
    }
}
