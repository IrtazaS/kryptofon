
package audio;

import utils.OctetBuffer;

/**
 *  Converts a 16-bit linear PCM stream from and to 8-bit A-law.
 *  
 *  @author Mikica B Kocic, based on Sun Microsystem's C source code.
 */
public class AudioCodecAlaw extends AbstractCODEC
{
    private int sampleSize;

    /**
     *  Constructs A-Law CODEC above existing PCM audio interface
     */
    public AudioCodecAlaw( AudioInterfacePCM audio ) 
    {
        this.audio = audio;
        this.sampleSize = this.audio.getSampleSize ();
        this.outputPcmBuf = new byte[ this.sampleSize ];
        this.inputPcmBuf = new byte[ this.sampleSize ];
    }

    /**
     * Gets the VoicePDU subclass attribute of the AbstractAudio object
     */
    public int getVoicePduSubclass () 
    {
        return protocol.VoicePDU.ALAW;
    }

    /**
     *  Returns the minimum sample size for use in creating buffers etc.
     */
    public int getSampleSize ()
    {
        return 160;
    }

    /**
     *  Encodes data to PCM, i.e. converts samples from A-Law to CODEC format.
     */
    public void convertToPCM( byte[] in, byte[] out )
    {
        OctetBuffer bb = OctetBuffer.wrap( out );

        for ( int i = 0; i < in.length; ++i ) 
        {
            bb.putShort( alaw2linear( in[i] ) );
        }
    }

    /**
     *  Decodes data from PCM, i.e. converts samples from CODEC to A-Law format.
     */
    public void convertFromPCM( byte[] in, byte[] out ) 
    {
        OctetBuffer bb = OctetBuffer.wrap( in );

        for( int i = 0; i < in.length / 2; ++i )
        {
            out[i] = linear2alaw( bb.getShort () );
        }
    }

    ///////////////////////////////////////////////////////////// ALGORITHM //////////////
    /*
     *  Converts a 16-bit linear PCM stream from and to 8-bit A-law.
     *  
     *  <pre>
     *     Linear Input Code    Compressed Code
     *     ------------------------ ---------------
     *     0000000wxyza         000wxyz
     *     0000001wxyza         001wxyz
     *     000001wxyzab         010wxyz
     *     00001wxyzabc         011wxyz
     *     0001wxyzabcd         100wxyz
     *     001wxyzabcde         101wxyz
     *     01wxyzabcdef         110wxyz
     *     1wxyzabcdefg         111wxyz
     *  </pre>
     *     
     *  This original source code is a product of Sun Microsystems, Inc. and is provided
     *  for unrestricted use. Users may copy or modify this source code without charge.
     *  For further information see John C. Bellamy's Digital Telephony, 1982,
     *  John Wiley & Sons, pps 98-111 and 472-476.
     */
    
    /* Sign bit */
    private final static int SIGN_BIT = 0x80;
    
    /* Quantization field mask.  */
    private final static int QUANT_MASK = 0xf;

    /* Left shift for segment number.  */
    private final static int SEG_SHIFT = 4;
    private final static int SEG_MASK = 0x70;
    
    /* Segment-ends lookup table */
    private final static int[] seg_end = {
            0x1F, 0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF
            };

    /**
     *  Converts a 16-bit linear PCM value to 8-bit A-law
     */
    private static byte linear2alaw( short pcm_value ) 
    {
        
        int pcm = pcm_value >> 3;

        int mask;
        if ( pcm >= 0 )
        {
            mask = 0xD5; // sign (7th) bit = 1 
            }
        else 
        {
            mask = 0x55; // sign bit = 0 
            pcm = -pcm - 1;
            }

        /* Convert the scaled magnitude to segment number. 
         */
        int seg = 8;
        
        for ( int i = 0; i < 8; ++i ) {
            if ( pcm <= seg_end[i] ) {
                seg = i;
                break;
            }
        }

        /* Combine the sign, segment, and quantization bits. 
         */
        if ( seg >= 8 ) { // out of range, return maximum value. 
            return (byte)( (  0x7F ^ mask ) & 0xFF - 0x100 );
        }

        int aval = ( seg << SEG_SHIFT ) & 0xFF;
        
        if ( seg < 2 ) {
            aval |= (pcm >> 1) & QUANT_MASK;
        } else {
            aval |= (pcm >> seg) & QUANT_MASK;
        }
        
        return (byte)( ( aval ^ mask ) & 0xFF - 0x100 );
    }

    /**
     *  Converts an 8-bit A-law value to 16-bit linear PCM
     */
    private static short alaw2linear( byte alaw_value )
    {
        
        int a_val = ( ( alaw_value + 0x100 ) & 0xFF ) ^ 0x55;
        
        int t = ( a_val & QUANT_MASK ) << 4;
        int seg = ( a_val & SEG_MASK ) >> SEG_SHIFT;
        
        switch ( seg )
        {
            case 0:
                t += 8;
                break;
            case 1:
                t += 0x108;
                break;
            default:
                t += 0x108;
                t <<= seg - 1;
            }

        return (short)( ( a_val & SIGN_BIT ) != 0 ? t : -t );        
    }
}

