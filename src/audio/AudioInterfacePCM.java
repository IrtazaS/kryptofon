
package audio;

import java.io.IOException;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

import protocol.VoicePDU;

import utils.Log;
import utils.OctetBuffer;

/**
 *  Implements the audio interface for 16-bit signed linear audio (PCM_SIGNED).
 *  It also provides support for CODECs that can convert to and from Signed LIN16.
 *
 *  @author Mikica B Kocic, based on <em>hevaily</em> modified 
 *          and re-documented Tim Panton's (thp@westhawk.co.uk) code 
 *          from org.asteriskjava.iax.audio.javasound.Audio8k
 */
public class AudioInterfacePCM implements AudioInterface 
{
    //////////////////////////////////////////////////////////////////////////////////////
    /* Constants
     */
    /** Audio buffering depth in number of frames */
    private static final int FRAME_COUNT = 10;

    /** Low-level water mark used for de-jittering */
    private static final int LLBS = 6;

    /** Frame interval in milliseconds */
    private static final int FRAME_INTERVAL = 20;

    //////////////////////////////////////////////////////////////////////////////////////
    /* Properties
     */
    /** Stereo recording */
    private boolean propertyStereoRec = false;
    
    /** Big buffers */
    private boolean propertyBigBuff = false;
    
    /** Input device name */
    private String propertyInputDeviceName = null;
    
    /** Output device name */
    private String propertyOutputDeviceName = null;
    
    //////////////////////////////////////////////////////////////////////////////////////
    /* Common file formats
     */
    private AudioFormat mono8k;
    private AudioFormat stereo8k;
    private AudioFormat mono44k;

    //////////////////////////////////////////////////////////////////////////////////////
    /* Audio Input (audio recorder interface from microphone)
     */
    private TargetDataLine targetDataLine = null;
    private volatile Thread audioSenderThread = null;
    private volatile Thread micRecorderThread = null;
    private volatile Packetizer audioSender = null;
    
    /* Audio input buffer (between microphone recorder and audio sender)
     */
    private AudioBuffer[] recordBuffer = new AudioBuffer[ FRAME_COUNT ];
    private int micBufPut = 0;
    private int micBufGet = 0;
    private long lastMicTimestamp = 0;
    
    //////////////////////////////////////////////////////////////////////////////////////
    /* Audio Output (audio player interface to speaker)
     */
    private SourceDataLine sourceDataLine = null;
    private volatile Thread audioPlayerThread = null;

    /* Dejitter buffer (between UDP and audio output)
     */
    private AudioBuffer[] playBuffer = new AudioBuffer[ FRAME_COUNT + FRAME_COUNT ];
    private int jitBufPut = 0; // incoming (enqueueing) packets here
    private int jitBufGet = 0; // ougoing packets (to audio output) from here
    private long jitBufFudge = 0; // total sample skew
    private boolean jitBufFirst = true; // flag whether it will be first packet in
    private boolean playerIsEnabled = false; // can write or not to audio output
    private long deltaTimePlayerMinusMic = 0; // used to calculate skew

    /** Measured call length in milliseconds */
    private long callLength = 0;

    /* Ringer tone generator (does not use dejitter buffer; writes directly
     * to audio output).
     */
    private volatile Thread ringerThread = null;
    private byte[] ringSamples = null;
    private byte[] silenceSamples = null;
    
    private boolean providingRingBack = false;
    private long ringTimer = -1;

    //////////////////////////////////////////////////////////////////////////////////////
    
    /**
     * Constructor for the AudioInterfacePCM object
     */
    public AudioInterfacePCM () 
    {
        this.mono8k = new AudioFormat(
                            AudioFormat.Encoding.PCM_SIGNED,
                            8000f, 16, 1, 2, 8000f, true );
        
        this.stereo8k = new AudioFormat(
                            AudioFormat.Encoding.PCM_SIGNED,
                            8000f, 16, 2, 4, 8000f, true );

        this.mono44k =  new AudioFormat(
                            AudioFormat.Encoding.PCM_SIGNED,
                            44100f, 16, 1, 2, 44100f, true );

        initializeRingerSamples ();

        //////////////////////////////////////////////////////////////////////////////////
        
        /*  Initializes source and target data lines used by the instance.
         */
        getAudioIn ();
        getAudioOut ();

        //////////////////////////////////////////////////////////////////////////////////
        
        if ( this.targetDataLine != null ) 
        {
            Runnable thread = new Runnable() {
                public void run () {
                    pduSenderWorker ();
                }
            };

            this.audioSenderThread = new Thread( thread, "Tick-send" );
            this.audioSenderThread.setDaemon( true );
            this.audioSenderThread.setPriority( Thread.MAX_PRIORITY - 1 );

            this.audioSenderThread.start();
        }

        //////////////////////////////////////////////////////////////////////////////////
        
        if ( this.sourceDataLine != null ) 
        {
            Runnable thread = new Runnable () {
                public void run () {
                    audioPlayerWorker ();
                }
            };

            this.audioPlayerThread = new Thread( thread, "Tick-play" );
            this.audioPlayerThread.setDaemon( true );
            this.audioPlayerThread.setPriority( Thread.MAX_PRIORITY );

            this.audioPlayerThread.start();
        }

        //////////////////////////////////////////////////////////////////////////////////
        
        if ( this.sourceDataLine != null ) 
        {
            Runnable thread = new Runnable () {
                public void run () {
                    ringerWorker ();
                }
            };
    
            this.ringerThread = new Thread( thread, "Ringer" );
            this.ringerThread.setDaemon( true );
            this.ringerThread.setPriority( Thread.MIN_PRIORITY );

            this.ringerThread.start ();
        }

        //////////////////////////////////////////////////////////////////////////////////

        if ( this.sourceDataLine == null ) {
            Log.attn( "Failed to open audio output device (speaker or similar)" );
        }

        if ( this.targetDataLine == null ) {
            Log.attn( "Failed to open audio capture device (microphone or similar)" );
        }
        
        Log.trace( "Created 8kHz 16-bit PCM audio interface; Sample size = " 
                + this.getSampleSize () + " octets" );
    }
    
    //////////////////////////////////////////////////////////////////////////////////////
    
    /**
     *  Returns preferred the minimum sample size for use in creating buffers etc.
     */
    @Override
    public int getSampleSize () 
    {
        AudioFormat af = this.mono8k;
        return (int) ( af.getFrameRate() * af.getFrameSize() * FRAME_INTERVAL / 1000.0 );
    }

    /**
     *  Returns our VoicePDU format
     */
    @Override
    public int getVoicePduSubclass () 
    {
        return protocol.VoicePDU.LIN16;
    }

    /**
     * Sets the active audio sender for the recorder
     */
    @Override
    public void setAudioSender( AudioInterface.Packetizer as ) 
    {
        this.audioSender = as;
    }

    //////////////////////////////////////////////////////////////////////////////////////

    /**
     *  Stops threads and cleans-up the instance.
     */
    @Override
    public void cleanUp ()
    {
        /* Signal all worker threads to quit
         */
        Thread spkout  = this.audioPlayerThread;
        Thread ringer  = this.ringerThread;
        Thread micin   = this.micRecorderThread;
        Thread sender  = this.audioSenderThread;

        this.audioPlayerThread = null;
        this.ringerThread      = null;
        this.micRecorderThread = null;
        this.audioSenderThread   = null;

        /* Wait for worker threads to complete
         */
        if ( spkout != null ) {
            try {
                spkout.interrupt ();
                spkout.join ();
            } catch ( InterruptedException e ) {
                /* ignored */
            }
        }

        if ( ringer != null ) {
            try {
                ringer.interrupt ();
                ringer.join ();
            } catch ( InterruptedException e ) {
                /* ignored */
            }
        }

        if ( micin != null ) {
            try {
                micin.interrupt ();
                micin.join ();
            } catch ( InterruptedException e ) {
                /* ignored */
            }
        }
        
        if ( sender != null ) {
            try {
                sender.interrupt ();
                sender.join ();
            } catch ( InterruptedException e ) {
                /* ignored */
            }
        }

        /* Be nice and close audio data lines
         */
        if ( this.sourceDataLine != null )
        {
            this.sourceDataLine.close ();
            this.sourceDataLine = null;
        }
        
        if ( this.targetDataLine != null )
        {
            this.targetDataLine.close ();
            this.targetDataLine = null;
        }
    }

    //////////////////////////////////////////////////////////////////////////////////////
    
    /**
     *  Writes frames to audio output i.e. source data line
     */
    private void audioPlayerWorker () 
    {
        Log.trace( "Thread started" );
        
        while( this.audioPlayerThread != null ) 
        {
            if ( this.sourceDataLine == null ) {
                break;
            }

            long next = this.writeBuffersToAudioOutput ();
            
            if ( next < 1 ) {
                next = FRAME_INTERVAL;
            }
            
            try {
                Thread.sleep( next );
            } catch( Throwable e ) {
                /* ignored */
            }
        }
        
        Log.trace( "Thread completed" );
        
        this.audioPlayerThread = null;
    }

    /**
     *  Writes de-jittered audio frames to audio output
     */
    private long writeBuffersToAudioOutput () 
    {
        if ( this.sourceDataLine == null ) {
            return 0;
        }
        
        int top = this.jitBufPut;
        if ( top - this.jitBufGet > this.playBuffer.length ) 
        {
            if ( this.jitBufGet == 0 ) {
                this.jitBufGet = top;
            } else {
                this.jitBufGet = top - this.playBuffer.length / 2;
            }
        }

        if ( ! this.playerIsEnabled )
        {
            /* We start when we have half full the buffers, FRAME_COUNT is 
             * usable buffer cap, size is twice that to keep history for AEC
             */
            if ( top - this.jitBufGet >= ( FRAME_COUNT + LLBS ) / 2 ) 
            {
                startPlay ();

                this.jitBufFirst = true;
            }
            else 
            {
                return FRAME_INTERVAL;
            }
        }

        int sz = 320;
        boolean fudgeSynch = true;
        int frameSize = this.sourceDataLine.getFormat().getFrameSize ();

        for ( ; this.jitBufGet <= top; ++this.jitBufGet ) 
        {
            AudioBuffer ab = this.playBuffer[ this.jitBufGet % this.playBuffer.length ];
            byte[] obuff = ab.getByteArray ();
            int avail = this.sourceDataLine.available() / (obuff.length + 2);
            sz = obuff.length;

            /* Take packet this.jitBufGet if available
             * Dejitter capacity: top - this.jitBufGet
             */
            if ( avail > 0 ) 
            {
                if ( ! ab.isWritten () ) // Missing packet
                {
                    /* Flag indicating whether we decide to conceal 
                     * vs to wait for missing data
                     */
                    boolean concealMissingBuffer = false;
                    
                    if ( avail > LLBS - 2 ) {
                        // Running out of sound
                        concealMissingBuffer = true;
                    }
                    if ( ( top - this.jitBufGet ) >= ( this.playBuffer.length - 2 ) ) {
                        // Running out of buffers
                        concealMissingBuffer = true;
                    }
                    if ( this.jitBufGet == 0 ) {
                        // No data to conceal with
                        concealMissingBuffer = false;
                    }
                    
                    /* Now conceal missing data or wait for it
                     */
                    if ( concealMissingBuffer ) {
                        concealMissingDataForAudioOutput(this.jitBufGet);
                    } else {
                        break; // Waiting for missing data
                    }
                }

                int start = 0;
                int len = obuff.length;
                
                /* We do adjustments only if we have a timing reference from mic
                 */
                if ( fudgeSynch && this.lastMicTimestamp > 0 
                        && this.lastMicTimestamp != Long.MAX_VALUE) 
                {
                    /* Only one per writeBuff call cause we depend on this.lastMicTime
                     */
                    fudgeSynch = false;
                    long delta = ab.getTimestamp() - this.lastMicTimestamp;

                    if ( this.jitBufFirst ) 
                    {
                        this.deltaTimePlayerMinusMic = delta;
                        this.jitBufFirst = false;
                    }
                    else 
                    {
                        /* If diff is positive, this means that the source clock is 
                         * running faster than the audio clock so we lop a few bytes
                         * off and make a note of the fudge factor.
                         * If diff is negative, this means the audio clock is faster 
                         * than the source clock so we make up a couple of samples
                         * and note down the fudge factor.
                         */
                        int diff = (int) ( delta - this.deltaTimePlayerMinusMic );
                        
                        /* We expect the output buffer to be full
                         */
                        int max = (int) Math.round( (LLBS / 2) * FRAME_INTERVAL);
                        
                        if ( Math.abs(diff) > FRAME_INTERVAL ) {
                            // "Delta = " + delta + " diff =" + diff );
                        }

                        if ( diff > max ) {
                            start = (diff > (LLBS * FRAME_INTERVAL)) ?
                                frameSize * 2 : frameSize; // panic ?
                            len -= start;
                            // Snip:  start / frameSize  sample(s)
                            this.jitBufFudge -= start / frameSize;
                        }
                        if (diff < -1 * FRAME_INTERVAL) {
                            this.sourceDataLine.write( obuff, 0, frameSize );
                            // Paste: added a sample
                            this.jitBufFudge += 1;
                        }
                    }
                }
                
                /* Now write data to audio output and mark audio buffer 'read'
                 */
                this.sourceDataLine.write( obuff, start, len );
                this.callLength += FRAME_INTERVAL;

                ab.setRead ();
            }
            else // No place for (more?) data in SDLB 
            {
                break;
            }
        }
        
        long ttd = ( ( sz * LLBS / 2 ) - this.sourceDataLine.available() ) / 8;
        return ttd;
    }

    /**
     *  Conceals missing data in the audio output buffer by averaging
     *  from samples taken from the the previous and next buffer.
     */
    private void concealMissingDataForAudioOutput( int n ) 
    {
        byte[] target = this.playBuffer[n % this.playBuffer.length].getByteArray();
        byte[] prev = this.playBuffer[ (n - 1) % this.playBuffer.length].getByteArray();
        byte[] next = this.playBuffer[ (n + 1) % this.playBuffer.length].getByteArray();
        
        /*  Creates a packet by averaging the corresponding bytes 
         *  in the surrounding packets hoping that new packet will sound better 
         *  than silence.
         *  TODO fix for 16-bit samples
         */
        for ( int i = 0; i < target.length; ++i ) 
        {
            target[i] = (byte) ( 0xFF & ( (prev[ i ] >> 1 ) + ( next[i] >> 1 ) ) );
        }
    }

    /**
     *  Writes directly to source line without buffering
     */
    @Override
    public void writeDirectly(byte[] buff) 
    {
        if ( this.sourceDataLine == null ) {
            return;
        }
        
        this.sourceDataLine.write( buff, 0, buff.length );
    }

    /**
     *  Enqueue packet for playing into de-jitter buffer.
     */
    @Override
    public void writeBuffered( byte[] buff, long timestamp ) throws IOException 
    {
        if ( this.sourceDataLine == null ) {
            return;
        }
        
        int fno = (int) ( timestamp / (AudioInterfacePCM.FRAME_INTERVAL ) );

        AudioBuffer ab = this.playBuffer[ fno % this.playBuffer.length ];
        byte nbuff[] = ab.getByteArray ();
        
        if ( propertyStereoRec )
        {
            for ( int i = 0; i < nbuff.length / 4; ++i )
            {
                nbuff[i * 4] = 0; // Left silent
                nbuff[i * 4 + 1] = 0; // Left silent
                nbuff[i * 4 + 2] = buff[i * 2];
                nbuff[i * 4 + 3] = buff[i * 2 + 1];
            }
        }
        else 
        {
            System.arraycopy( buff, 0, nbuff, 0, nbuff.length );
        }
        
        ab.setWritten ();
        ab.setTimestamp( timestamp );
        
        this.jitBufPut = fno;
    }

    //////////////////////////////////////////////////////////// VOICE PDU SENDER ////////
    
    /**
     *  Sends audio frames to UDP channel at regular intervals (ticks)
     */
    private void pduSenderWorker () 
    {
        Log.trace( "Thread started" );
        
        long set = 0;
        long last, point = 0;
        boolean audioTime = false;

        while( this.audioSenderThread != null )
        {
            if ( this.targetDataLine == null ) {
                break;
            }

            /* This should be current time: interval += FRAME_INTERVAL
             */
            point += FRAME_INTERVAL;
            
            /* Delta time
             */
            long delta = point - set + FRAME_INTERVAL;
            
            if ( this.targetDataLine.isActive () ) 
            {
                if ( ! audioTime ) // Take care of "discontinuous time"
                {
                    audioTime = true;
                    set = this.targetDataLine.getMicrosecondPosition() / 1000;
                    last = point = set;
                }
            }
            else 
            {
                point = 0;
                delta = FRAME_INTERVAL; // We are live before TDL
                set = System.currentTimeMillis (); // For ring cadence
                audioTime = false;
            }
            
            sendAudioFrame( set );
            
            // If we are late, set is larger than last so we sleep less
            // If we are early, set is smaller than last and we sleep longer
            //
            if ( delta > 1 ) // Only sleep if it is worth it...
            {
                try {
                    Thread.sleep( delta );
                } catch( InterruptedException e ) {
                    /* ignored */
                }
            }

            last = set;
            
            if ( audioTime ) {
                set = this.targetDataLine.getMicrosecondPosition() / 1000;
            }
            
            if ( point > 0 ) {
                Log.audio( "Ticker: slept " + delta + " from " + last + ", now " + set );
            }
        }
        
        Log.trace( "Thread completed" );

        this.audioSenderThread = null;
    }

    /**
     *  Called every FRAMEINTERVAL ms to send audio frame
     */
    private void sendAudioFrame( long set )
    {
        if ( this.audioSender == null ) {
            return;
        }

        try {
            this.audioSender.send ();
        } catch( IOException e ) {
            Log.exception( Log.WARN, e );
        }
    }

    //////////////////////////////////////////////////////////// AUDIO INPUT /////////////
    
    /**
     *  Records audio samples from the microphone
     */
    private void micRecorderWorker () 
    {
        Log.trace( "Thread started" );
        
        while( this.micRecorderThread != null ) 
        {
            if ( this.targetDataLine == null ) {
                break;
            }

            micDataRead ();
        }
        
        Log.trace( "Thread stopped" );
        
        this.micRecorderThread = null;
    }

    /**
     *  Called from micRecorder to record audio samples from microphone.
     *  Blocks as needed.
     */
    private void micDataRead () 
    {
        try 
        {
            int fresh = this.micBufPut % this.recordBuffer.length;
            AudioBuffer ab = this.recordBuffer[ fresh ];
            byte[] buff = ab.getByteArray ();
            
            this.targetDataLine.read( buff, 0, buff.length );
            
            long stamp = this.targetDataLine.getMicrosecondPosition () / 1000;
            if ( stamp >= this.lastMicTimestamp )
            {
                if ( ab.isWritten () ) {
                    // Overrun audio data: stamp + "/" + got
                }

                ab.setTimestamp( stamp );
                ab.setWritten (); // should test for overrun ???
                
                /* Out audio data into buffer: 
                 * fresh + " " + ab.getTimestamp() + "/" + this.micBufPut
                 */
                
                ++this.micBufPut;
            }
            else // Seen at second and subsequent activations, garbage data 
            {
                /* Drop audio data */
            }
            
            this.lastMicTimestamp = stamp;
        }
        catch( Exception e ) 
        {
            /* ignored */
        }
    }

    /**
     *  Read from the Microphone, into the buffer provided, 
     *  but <em>only</em> filling getSampSize() bytes.
     *  Returns the timestamp of the sample from the audio clock.
     *
     *  @param  buff audio samples
     *  @return the timestamp of the sample from the audio clock.
     *  @exception IOException Description of Exception
     */
    @Override
    public long readWithTimestamp( byte[] buff ) throws IOException
    {
        int micnext = this.micBufGet % this.recordBuffer.length;
        int buffCap = (this.micBufPut - this.micBufGet ) % this.recordBuffer.length;
        long timestamp = 0;
        
        Log.audio( "Getting audio data from buffer " + micnext + "/" + buffCap );

        AudioBuffer ab = this.recordBuffer[ micnext ];
        if ( ab.isWritten () 
                && ( this.micBufGet > 0 || buffCap >= this.recordBuffer.length / 2 ) ) 
        {
            timestamp = ab.getTimestamp ();
            resample( ab.getByteArray(), buff );
            ab.setRead ();

            ++this.micBufGet;
        }
        else 
        {
            System.arraycopy( this.silenceSamples, 0, buff, 0, buff.length );
            Log.audio( "Sending silence" );
            timestamp = ab.getTimestamp (); // or should we warn them ??
        }

        return timestamp;
    }

    /**
     *  Simple PCM down sampler.
     *
     * @param src   source buffer with audio samples
     * @param dest  destination buffer with audio samples
     */
    private void resample( byte[] src, byte[] dest ) 
    {
        if ( src.length == dest.length ) 
        {
            /* Nothing to down sample; copy samples as-is to destination
             */
            System.arraycopy( src, 0, dest, 0, src.length );
            return;
        }
        else if ( src.length / 2 == dest.length )
        {
            /* Source is stereo, send the left channel
             */
            for ( int i = 0; i < dest.length / 2; i++ ) 
            {
                dest[i * 2] = src[i * 4];
                dest[i * 2 + 1] = src[i * 4 + 1];
            }
            return;
        }

        /* Now real work. We assume that it is 44k stereo 16-bit and will down-sample
         * it to 8k (but not very clever: no anti-aliasing etc....).
         */
        OctetBuffer srcBuffer = OctetBuffer.wrap( src );
        OctetBuffer destBuffer = OctetBuffer.wrap( dest );
        
        /* Iterate over the values we have, add them to the target bucket they fall into
         * and count the drops....
         */
        int drange = dest.length / 2;
        double v[] = new double[ drange ];
        double w[] = new double[ drange ];

        double frequencyRatio = 8000.0 / 44100.0;
        int top = src.length / 2;
        for ( int eo = 0; eo < top; ++eo ) 
        {
            int samp = (int) Math.floor( eo * frequencyRatio );
            if ( samp >= drange ) {
                samp = drange - 1;
            }
            v[ samp ] += srcBuffer.getShort( eo * 2 );
            w[ samp ]++;
        }
        
        /* Now re-weight the samples to ensure no volume quirks and move to short
         */
        short vw = 0;
        for ( int ei = 0; ei < drange; ++ei ) 
        {
            if ( w[ei] != 0 ) {
                vw = (short) ( v[ei] / w[ei] );
            }
            destBuffer.putShort( ei * 2, vw );
        }
    }

    //////////////////////////////////////////////////////////// RINGER //////////////////
    
    /**
     *  Writes ring signal samples to audio output
     */
    private void ringerWorker ()
    {
        Log.trace( "Thread started" );
        
        while( this.ringerThread != null )
        {
            if ( this.sourceDataLine == null ) {
                break;
            }

            long nap = 100; // default sleep in millis when idle
            
            if ( this.providingRingBack ) 
            {
                nap = 0;
                while( nap < FRAME_INTERVAL ) 
                {
                    boolean inRing = ( ( this.ringTimer++ % 120 ) < 40 );
                    if ( inRing ) {
                        nap = this.writeDirectIfAvail( this.ringSamples );
                    } else {
                        nap = this.writeDirectIfAvail( this.silenceSamples );
                    }
                }
            }

            try {
                Thread.sleep( nap );
            } catch( InterruptedException ex ) {
                /* ignored */
            }
        }
        
        Log.trace( "Thread completed" );
        
        this.ringerThread = null;
    }

    /**
     *  Writes audio samples to audio output directly (without using jitter buffer).
     *  
     *  @return milliseconds to sleep (after which time next write should occur)
     */
    private long writeDirectIfAvail( byte[] samples ) 
    {
        if ( this.sourceDataLine == null ) {
            return 0;
        }

        if ( this.sourceDataLine.available () > samples.length ) {
            this.sourceDataLine.write( samples, 0, samples.length );
        }

        long nap = ( samples.length * 2 - this.sourceDataLine.available () ) / 8;
        return nap;
    }

    /**
     *  Initializes ringer samples (ring singnal and silecce) samples
     */
    private void initializeRingerSamples ()
    {
        /* First generate silence
         */
        int numOfSamples = this.getSampleSize ();
        this.silenceSamples = new byte[ numOfSamples ];
        
        /* Now generate ringing tone as two superimposed frequencies.
         */
        double freq1 =  25.0 / 8000;
        double freq2 = 420.0 / 8000;
        
        OctetBuffer rbb = OctetBuffer.allocate( numOfSamples );

        for ( int i = 0; i < 160; ++i )
        {
            short s = (short) ( Short.MAX_VALUE
                               * Math.sin( 2.0 * Math.PI * freq1 * i )
                               * Math.sin( 4.0 * Math.PI * freq2 * i )
                               / 4.0  /* signal level ~= -12 dB */
                               );
            rbb.putShort( s );
        }
        
        this.ringSamples = rbb.getStore ();
        
    }

    //////////////////////////////////////////////////////////// AUDIO OUTPUT ////////////
    
    /**
     *  Get audio output. Initializes source data line.
     */
    private boolean getAudioOut () 
    {
        this.sourceDataLine = null;
        boolean succeded = false;
        
        AudioFormat af;
        String name;
        if ( propertyStereoRec ) {
            af = this.stereo8k;
            name = "stereo8k";
        } else {
            af = this.mono8k;
            name = "mono8k";
        }

        int buffsz = (int) Math.round( LLBS * af.getFrameSize() * af.getFrameRate() *
                                      FRAME_INTERVAL / 1000.0 );
        
        if ( propertyBigBuff ) {
            buffsz *= 2.5;
        }
        
        /* We want to do tricky stuff on the 8k mono stream before
         * play back, so we accept no other sort of line.
         */
        String pref = propertyOutputDeviceName;
        SourceDataLine sdl = findSourceDataLineByPref( pref, af, name, buffsz );
        if ( sdl != null )
        {
            int outputBufferSize = (int) ( af.getFrameRate() * af.getFrameSize() / 50.0 );
            
            Log.trace( "Output Buffer Size = " + outputBufferSize );

            for ( int i = 0; i < this.playBuffer.length; ++i ) {
                this.playBuffer[i] = new AudioBuffer( outputBufferSize );
            }

            this.sourceDataLine = sdl;
            succeded = true;
        }
        else
        {
            Log.warn( "No audio output device available" );
        }

        return succeded;
    }

    /**
     *  Returns audio input (target data line)
     */
    private boolean getAudioIn ()
    {
        this.targetDataLine = null;
        boolean succeded = false;

        /* Make a list of formats we can live with 
         */
        String names[] = { "mono8k", "mono44k" };
        AudioFormat[] afsteps = { this.mono8k, this.mono44k };
        if ( propertyStereoRec )  {
            names[0] = "stereo8k";
            afsteps[0] = this.stereo8k;
        }

        int[] smallbuff = {
            (int) Math.round( LLBS * afsteps[0].getFrameSize() * afsteps[0].getFrameRate() 
                    * FRAME_INTERVAL / 1000.0 ),
            (int) Math.round( LLBS * afsteps[1].getFrameSize() * afsteps[1].getFrameRate() 
                    * FRAME_INTERVAL / 1000.0 )
            };
        
        /* If LLBS > 4 then these can be the same ( Should tweak based on LLB really.)
         */
        int[] bigbuff = smallbuff;

        /* choose one based on audio properties
         */
        int[] buff = propertyBigBuff ? bigbuff : smallbuff;

        /* now try and find a device that will do it - and live up to the preferences
         */
        String pref = propertyInputDeviceName;
        int fno = 0;
        this.targetDataLine = null;
        for ( ; fno < afsteps.length && this.targetDataLine == null; ++fno ) 
        {
            this.targetDataLine = fintTargetDataLineByPref(pref, 
                    afsteps[fno], names[fno], buff[fno] );
        }
        
        if ( this.targetDataLine != null ) 
        {
            Log.audio( "TargetDataLine =" + this.targetDataLine + ", fno = " + fno );
            
            AudioFormat af = this.targetDataLine.getFormat ();
            
            /* now allocate some buffer space in the raw format
             */
            
            int inputBufferSize = (int) ( af.getFrameRate() * af.getFrameSize() / 50.0 );
            
            Log.trace( "Input Buffer Size = " + inputBufferSize );
            
            for ( int i = 0; i < this.recordBuffer.length; ++i ) {
                this.recordBuffer[i] = new AudioBuffer( inputBufferSize );
            }
            
            succeded = true;
        }
        else 
        {
            Log.warn( "No audio input device available" );
        }

        return succeded;
    }

    /**
     *  Searches for data line of either sort (source/targe) based on the pref string. 
     *  Uses type to determine the sort ie Target or Source. 
     *  debugInfo parameter is only used in debug printouts to set the context.
     */
    private DataLine findDataLineByPref( String pref, AudioFormat af,
            String name, int sbuffsz, Class<?> lineClass,
            String debugInfo ) 
    {
        DataLine line = null;
        DataLine.Info info = new DataLine.Info(lineClass, af);
        try 
        {
            if ( pref == null ) 
            {
                line = (DataLine) AudioSystem.getLine( info );
            }
            else 
            {
                Mixer.Info[] mixes = AudioSystem.getMixerInfo();
                for ( int i = 0; i < mixes.length; ++i ) 
                {
                    Mixer.Info mixi = mixes[i];
                    String mixup = mixi.getName().trim();
                    Log.audio( "Mix " + i + " " + mixup );
                    if ( mixup.equals( pref ) ) 
                    {
                        Log.audio( "Found name match for prefered input mixer" );
                        
                        Mixer preferedMixer = AudioSystem.getMixer( mixi );
                        if ( preferedMixer.isLineSupported( info ) ) 
                        {
                            line = (DataLine) preferedMixer.getLine( info );
                            Log.audio( "Got " + debugInfo + " line" );
                            break;
                        }
                        else 
                        {
                            Log.audio( debugInfo + " format not supported" );
                        }
                    }
                }
            }
        }
        catch( Exception e )
        {
            Log.warn( "Unable to get a " + debugInfo + " line of type: " + name );
            line = null;
        }

        return line;
    }

    /**
     *  Searches for target data line according to preferences.
     */
    private TargetDataLine fintTargetDataLineByPref( String pref, 
            AudioFormat af, String name, int sbuffsz )
    {
        String debugInfo = "recording";
        
        TargetDataLine line = (TargetDataLine) findDataLineByPref(pref, af, name, 
                sbuffsz, TargetDataLine.class, debugInfo );
        
        if ( line != null )
        {
            try 
            {
                line.open( af, sbuffsz );
                Log.audio( "Got a " + debugInfo + " line of type: " + name
                        + ", buffer size = " + line.getBufferSize () );
            }
            catch( LineUnavailableException e ) 
            {
                Log.warn( "Unable to get a " + debugInfo + " line of type: " + name );
                line = null;
            }
        }

        return line;
    }

    /**
     *  Searches for source data line according to preferences.
     */
    private SourceDataLine findSourceDataLineByPref( String pref, 
            AudioFormat af, String name, int sbuffsz )
    {
        String debtxt = "play";
        
        SourceDataLine line = (SourceDataLine) findDataLineByPref( pref, af, name, 
                sbuffsz, SourceDataLine.class, debtxt );
        
        if ( line != null )
        {
            try 
            {
                line.open( af, sbuffsz );
                Log.audio( "Got a " + debtxt + " line of type: " + name
                        + ", buffer size = " + line.getBufferSize () );
            }
            catch( LineUnavailableException e ) 
            {
                Log.warn( "Unable to get a " + debtxt + " line of type: " + name );
                line = null;
            }
        }
        return line;
    }

    //////////////////////////////////////////////////////////////////////////////////////

    /**
     *  Stops the audio recording worker thread
     */
    @Override
    public void stopRecording () 
    {
        if ( this.targetDataLine == null ) {
            return;
        }            

        Log.audio( "Stopped recoring ");

        this.targetDataLine.stop ();
        this.micRecorderThread = null;
        this.audioSender = null;
    }

    /**
     *  Start the audio recording worker thread
     */
    @Override
    public long startRecording ()
    {
        if ( this.targetDataLine == null ) {
            return 0;
        }
        
        Log.audio( "Started recording" );

        if ( this.targetDataLine.available() > 0 ) 
        {
            Log.audio( "Flushed recorded data" );
            this.targetDataLine.flush ();
            this.lastMicTimestamp = Long.MAX_VALUE; // Get rid of spurious samples
        } 
        else 
        {
            this.lastMicTimestamp = 0;
        }
        
        this.targetDataLine.start ();

        /* Clean receive buffers pointers 
         */
        this.micBufPut = this.micBufGet = 0;
        for ( int i = 0; i < this.recordBuffer.length; ++i ) {
            this.recordBuffer[i].setRead ();
        }

        Runnable thread = new Runnable() {
            public void run () {
                micRecorderWorker ();
            }
        };

        this.micRecorderThread = new Thread( thread, "Tick-rec" );
        this.micRecorderThread.setDaemon( true );
        this.micRecorderThread.setPriority( Thread.MAX_PRIORITY - 1 );
        this.micRecorderThread.start ();

        return this.targetDataLine.getMicrosecondPosition() / 1000;
    }

    /**
     *  Starts the audio output worker thread
     */
    @Override
    public void startPlay () 
    {
        if ( this.sourceDataLine == null ) {
            return;
        }
        
        Log.audio( "Started playing" );
        
        /* Reset the dejitter buffer
         */
        this.jitBufPut = 0;
        this.jitBufGet = 0;
        this.jitBufFudge = 0;
        this.callLength = 0;

        this.sourceDataLine.flush ();
        this.sourceDataLine.start ();

        this.playerIsEnabled = true;
    }

    /**
     *  Stops the audio output worker thread
     */
    public void stopPlay ()
    {
        /* Reset the buffer
         */
        this.jitBufPut = 0;
        this.jitBufGet = 0;
        this.playerIsEnabled = false;

        if ( this.sourceDataLine == null ) {
            return;
        }

        Log.audio( "Stopped playing" );

        this.sourceDataLine.stop ();
    
        if ( this.jitBufFudge != 0 )
        {
            Log.audio( "Total sample skew: " + this.jitBufFudge );
            Log.audio( "Percentage: " + (100.0 * this.jitBufFudge / (8 * this.callLength)) );
            
            this.jitBufFudge = 0;
        }
        
        if ( this.callLength > 0 ) {
            Log.trace( "Total call Length: " + this.callLength + " ms" );
        }
        
        this.sourceDataLine.flush ();
    }

    /**
     *  Gets audio interface by VoicePDU format
     *
     * @return AudioInterfacePCM
     */
    @Override
    public AudioInterface getByFormat( Integer format ) 
    {
        AudioInterface ret = null;
        switch( format.intValue () ) 
        {
            case VoicePDU.ALAW:
                ret = new AudioCodecAlaw(this);
                break;
            case VoicePDU.ULAW:
                ret = new AudioCodecUlaw(this);
                break;
            case VoicePDU.LIN16:
                ret = this;
                break;
            default:
                Log.warn( "Invalid format for Audio " + format.intValue () );
                Log.warn( "Forced uLaw " );
                ret = new AudioCodecUlaw(this);
                break;
        }
        
        Log.audio( "Using audio Interface of type : " + ret.getClass().getName() );
        
        return ret;
    }

    /**
     *  Starts ringing signal
     */
    @Override
    public void startRinging ()
    {
        if ( this.sourceDataLine == null ) {
            return;
        }

        this.sourceDataLine.flush();
        this.sourceDataLine.start();
        this.providingRingBack = true;
    }

    /**
     *  Stops ringing singnal
     */
    @Override
    public void stopRinging () 
    {
        if ( this.sourceDataLine == null ) {
            return;
        }

        if ( this.providingRingBack ) 
        {
            this.providingRingBack = false;
            this.ringTimer = -1;
            
            this.sourceDataLine.stop();
            this.sourceDataLine.flush();
        }
    }

}
