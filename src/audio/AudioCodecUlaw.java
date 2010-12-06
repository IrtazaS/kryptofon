
package audio;

import utils.OctetBuffer;

/**
 *  Converts a 16-bit linear PCM stream from and to 8-bit u-law.
 *  
 *  @author Mikica B Kocic, based on Craig Reese's and Joe Campbell's C source code.
 */
public class AudioCodecUlaw extends AbstractCODEC 
{
    private int sampleSize;

    /**
     *  Constructs A-Law CODEC above existing PCM audio interface
     */
    public AudioCodecUlaw( AudioInterfacePCM audio ) 
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
        return protocol.VoicePDU.ULAW;
    }

    /**
     *  Returns the minimum sample size for use in creating buffers etc.
     */
    public int getSampleSize ()
    {
        return 160;
    }

    /**
     *  Encodes data to PCM, i.e. converts samples from u-Law to CODEC format.
     */
    public void convertToPCM( byte[] in, byte[] out )
    {
        OctetBuffer bb = OctetBuffer.wrap(out);
        for (int i = 0; i < in.length; i++) {
          bb.putShort( ulaw2linear( in[i] ) );
        }
    }

    /**
     *  Decodes data from PCM, i.e. converts samples from u-Law to PCM format.
     */
    public void convertFromPCM( byte[] in, byte[] out )
    {
        OctetBuffer bb = OctetBuffer.wrap(in);
        for ( int i = 0; i < out.length; ++i )
        {
            out[i] = linear2ulaw( bb.getShort () );
        }
    }

    ///////////////////////////////////////////////////////////// ALGORITHM //////////////
    /*
     *  Craig Reese: IDA/Supercomputing Research Center
     *  Joe Campbell: Department of Defense
     *  29 September 1989
     * 
     *  References:
     *  1) CCITT Recommendation G.711  (very difficult to follow)
     *  2) "A New Digital Technique for Implementation of Any
     *      Continuous PCM Companding Law," Villeret, Michel,
     *      et al. 1973 IEEE Int. Conf. on Communications, Vol 1,
     *      1973, pg. 11.12-11.17
     *  3) MIL-STD-188-113,"Interoperability and Performance Standards
     *      for Analog-to_Digital Conversion Techniques,"
     *      17 February 1987
     */

    private static final boolean ZEROTRAP = true;
    private static final short   BIAS = 0x84;
    private static final int     CLIP = 32635;
    
    private static final int exp_lut1 [] = 
    {
        0, 0, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3,
        4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
        5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5,
        5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5,
        6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
        6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
        6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
        6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
        7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7
    };

    /**
     * Converts a linear signed 16bit sample to a uLaw byte.
     */
    public static byte linear2ulaw( int sample )
    {
        int sign, exponent, mantissa, ulawbyte;

        if ( sample > 32767 ) {
            sample = 32767;
        }
        else if ( sample < -32768 ) {
            sample = -32768;
            /* Get the sample into sign-magnitude. */
        }
        
        sign = (sample >> 8) & 0x80; /* set aside the sign */
        if ( sign != 0 ) {
            sample = -sample; /* get magnitude */
        }
        
        if ( sample > CLIP ) {
            sample = CLIP; /* clip the magnitude */
            /* Convert from 16 bit linear to ulaw. */
        }
        
        sample = sample + BIAS;
        exponent = exp_lut1[ (sample >> 7) & 0xFF];
        mantissa = (sample >> (exponent + 3)) & 0x0F;
        ulawbyte = ~ (sign | (exponent << 4) | mantissa);
        
        if ( ZEROTRAP ) {
            if ( ulawbyte == 0 ) {
                ulawbyte = 0x02; /* optional CCITT trap */
            }
        }
        
        return (byte) ulawbyte;
    }

    /* u-Law to linear conversion table
     */
    private static short[] ulaw2lin_table = 
    {
        -32124, -31100, -30076, -29052, -28028, -27004, -25980, -24956,
        -23932, -22908, -21884, -20860, -19836, -18812, -17788, -16764,
        -15996, -15484, -14972, -14460, -13948, -13436, -12924, -12412,
        -11900, -11388, -10876, -10364,  -9852,  -9340,  -8828,  -8316,
         -7932,  -7676,  -7420,  -7164,  -6908,  -6652,  -6396,  -6140,
         -5884,  -5628,  -5372,  -5116,  -4860,  -4604,  -4348,  -4092,
         -3900,  -3772,  -3644,  -3516,  -3388,  -3260,  -3132,  -3004,
         -2876,  -2748,  -2620,  -2492,  -2364,  -2236,  -2108,  -1980,
         -1884,  -1820,  -1756,  -1692,  -1628,  -1564,  -1500,  -1436,
         -1372,  -1308,  -1244,  -1180,  -1116,  -1052,   -988,   -924,
          -876,   -844,   -812,   -780,   -748,   -716,   -684,   -652,
          -620,   -588,   -556,   -524,   -492,   -460,   -428,   -396,
          -372,   -356,   -340,   -324,   -308,   -292,   -276,   -260,
          -244,   -228,   -212,   -196,   -180,   -164,   -148,   -132,
          -120,   -112,   -104,    -96,    -88,    -80,   - 72,    -64,
           -56,    -48,    -40,    -32,    -24,    -16,     -8,      0,
         32124,  31100,  30076,  29052,  28028,  27004,  25980,  24956,
         23932,  22908,  21884,  20860,  19836,  18812,  17788,  16764,
         15996,  15484,  14972,  14460,  13948,  13436,  12924,  12412,
         11900,  11388,  10876,  10364,   9852,   9340,   8828,   8316,
          7932,   7676,   7420,   7164,   6908,   6652,   6396,   6140,
          5884,   5628,   5372,   5116,   4860,   4604,   4348,   4092,
          3900,   3772,   3644,   3516,   3388,   3260,   3132,   3004,
          2876,   2748,   2620,   2492,   2364,   2236,   2108,   1980,
          1884,   1820,   1756,   1692,   1628,   1564,   1500,   1436,
          1372,   1308,   1244,   1180,   1116,   1052,    988,    924,
           876,    844,    812,    780,    748,    716,    684,    652,
           620,    588,    556,    524,    492,    460,    428,    396,
           372,    356,    340,    324,    308,    292,    276,    260,
           244,    228,    212,    196,    180,    164,    148,    132,
           120,    112,    104,     96,     88,     80,     72,     64,
            56,     48,     40,     32,     24,     16,      8,      0
    };
    
    /**
     *  Converts an 8-bit u-law value to 16-bit linear PCM
     */
    public static short ulaw2linear( byte ulawbyte )
    {
        return ulaw2lin_table[ ulawbyte & 0xFF ];
    }
}
