
/**
 *  Utilities to handle base64 encoding, log and octet buffers
 */
package utils;

import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;

/**
 *  Common application message logger facility (static implementation)
 *  
 *  @author Mikica B Kocic
 */
public enum Log
{
    /** Log all messages   */  ALL     ( 0xFFFF, "All" ),
    /** Log audio messages */  AUDIO   ( 0x0100, "Audio" ),
    /** Log verbose PDUs   */  VERB    ( 0x0080, "Verbose" ),
    /** Log binary PDUs    */  PDU     ( 0x0040, "PDU" ),
    /** Log debug messages */  DEBUG   ( 0x0020, "Debug" ),
    /** Log attn messages  */  ATTN    ( 0x0010, "Attention" ),
    /** Log program trace  */  TRACE   ( 0x0008, "Trace" ),
    /** Log information    */  INFO    ( 0x0004, "Info" ),
    /** Log warnings       */  WARN    ( 0x0002, "Warning" ),
    /** Log errors         */  ERROR   ( 0x0001, "Error" );
    
    public interface AttentionContext
    {
        public abstract void attention( String message );
    }
    
    /**
     *  Log channel bitmap
     */
    private int mask;
    
    /**
     *  Log channel description
     */
    private String desc;

    /**
     *  Current channels to be logged. By default only the Error channel is enabled.
     */
    private static Log showChannels = ERROR;

    /**
     *  Print stream for standard messages
     */
    public static PrintStream out = System.out;
    
    /**
     *  Print stream for error messages
     */
    public static PrintStream err = System.err;
    
    /**
     *  Instance of the interface for attention messages
     */
    public static AttentionContext attn = null;

    /**
     *  Private constructor that forbids instantiation by the user
     */
    private Log( int mask, String desc ) 
    {
        this.mask = mask;
        this.desc = desc;
        }

    /**
     *  Enables/disables the Log channel 
     */
    public static void setEnabled( Log channel, boolean on )
    {
        synchronized( showChannels )
        {
            if ( on  ) {
                showChannels.mask |= channel.mask;
            } else {
                showChannels.mask &= ~channel.mask;
            }
        }
    }

    /**
     *  Returns if log channel is enabled
     */
    public static boolean isEnabled( Log channel )
    {
        synchronized( showChannels ) 
        {
            return ( showChannels.mask & channel.mask ) == channel.mask;
        }
    }

    /**
     *  Logs message with prefix and time-stamp
     */
    private static void println( Log channel, String message )
    {
        synchronized( showChannels ) 
        {
            if ( ( showChannels.mask & channel.mask ) != channel.mask ) {
                return;
            }
        }
        
        String prefix = nowMillis() + " " + channel.desc 
                    + " [" + Thread.currentThread().getName () + "] ";

        PrintStream os = out;
        if ( channel == ERROR || channel == WARN || channel == ATTN ) {
            os = err;
        }

        os.println( prefix + message ); 
        os.flush ();
    }
    
    /**
     * Logs a warning message.
     */
    public static void error( String string ) 
    {
        println( ERROR, string );
    }
    
    /**
     * Logs a warning message.
     */
    public static void warn( String string ) 
    {
        println( WARN, string );
    }

    /**
     * Logs a informational message.
     */
    public static void info( String string )
    {
        println( INFO, string );
    }

    /**
     * Logs a debug message.
     */
    public static void debug( String string )
    {
        println( DEBUG, string );
    }

    /**
     * Logs a attention message.
     */
    public static void attn( String string )
    {
        if ( attn == null ) {
            println( ATTN, string );
            return;
        }

        synchronized( showChannels ) 
        {
            if ( ( showChannels.mask & ATTN.mask ) != ATTN.mask ) {
                return;
            }
        }

        attn.attention( string );
    }

    /**
     * Logs a program trace message.
     */
    public static void trace( String string )
    {
        println( TRACE, string );
    }

    /**
     * Logs a protocol data unit
     */
    public static void pdu( String string )
    {
        println( PDU, string );
    }

    /**
     * Logs a verbose message.
     */
    public static void verb( String string )
    {
        println( VERB, string );
    }

    /**
     * Logs a audio message.
     */
    public static void audio( String string )
    {
        println( AUDIO, string );
    }

    /**
     * Prints where this message was called from, via a stack trace.
     */
    public static void where ()
    {
        Exception e = new Exception( "Called from:" );
        e.printStackTrace ();
    }
    
    /**
     *  Returns current callee's name of the method, file, location and
     *  description of the exception.
     */
    public static void exception( Log channel, Exception ex )
    {
        synchronized( showChannels ) 
        {
            if ( ( showChannels.mask & channel.mask ) != channel.mask ) {
                return;
            }
        }

        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace ();

        for ( int i = 0; i < stackTrace.length; ++i ) 
        {
            if ( stackTrace[i].getClassName().endsWith( "Log" )
                    && stackTrace[i].getMethodName().equals( "loc" ) ) // This is us. 
            {
                /* Next is callee 
                 * */
                if ( ++i < stackTrace.length )
                {
                    StringBuffer sb = new StringBuffer ();
                    sb.append( stackTrace[i].getMethodName() )
                      .append( " (" )
                      .append( stackTrace[i].getFileName() )
                      .append( ":" )
                      .append( stackTrace[i].getLineNumber() )
                      .append( ") " )
                      .append( ex.toString () );
                    
                    /* Next is the list of callee's callee
                    ++i;
                    for( int j = 0; i < stackTrace.length && stackTrace[i].getFileName() != null; ++i, ++j ) 
                    {
                        sb.append( j == 0 ? "\nStack: " : ", " );
                        sb.append( stackTrace[i].getMethodName() )
                          .append( " (" )
                          .append( stackTrace[i].getFileName() )
                          .append( ":" )
                          .append( stackTrace[i].getLineNumber() )
                          .append( ")" );
                    }
                     */

                    println( channel, sb.toString () );
                    return;
                }

                break;
            }
        }

        println( channel, ex.toString () );
    }
    
    /**
     *  Converts a byte array into a hex string, using the specified separator.
     *
     *  @param array     The byte array
     *  @param length    The length to print
     *  @param separator The separator (may be null)
     *  @return          The hex character string
     */
    public static String toHex( byte[] array, int length, String separator )
    {
        length = Math.min( length, array.length );
        
        StringBuffer ret = new StringBuffer( 64 );

        for( int i = 0; i < length; ++i )
        {
            int value = ( array[i] + 0x100 ) & 0xFF;
            int hi    = value >> 4;
            int low   = value & 0x0F;
            
            final char[] hexChars = {
                    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 
                    'A', 'B', 'C', 'D', 'E', 'F'
                    };

            ret.append( hexChars[hi] ).append( hexChars[low] );
            
            if ( separator != null ) {
                ret.append( separator );
            }
        }
        return ret.toString ();
    }
    
    /**
     *  Converts a byte array into a hex string, using the specified separator.
     */
    public static String toHex( byte[] array, String separator )
    {
        return array == null ? "" : toHex( array, array.length, separator );
    }
    
    /**
     *  Converts a byte array into a hex string.
     */
    public static String toHex( byte[] array )
    {
        return array == null ? "" : toHex( array, array.length, null );
    }
    
    /**
     *  Gets current time stamp -- date only
     */
    public static String nowDate ()
    {
        Calendar cal = Calendar.getInstance ();
        SimpleDateFormat sdf = new SimpleDateFormat( "yyyy-MM-dd" );
        return sdf.format( cal.getTime() );
    }
    
    /**
     *  Gets current time stamp -- time only
     */
    public static String nowTime ()
    {
        Calendar cal = Calendar.getInstance ();
        SimpleDateFormat sdf = new SimpleDateFormat( "HH:mm:ss" );
        return sdf.format( cal.getTime() );
    }

    /**
     *  Gets current time stamp -- date and time in ISO format 
     */
    public static String now ()
    {
        Calendar cal = Calendar.getInstance ();
        SimpleDateFormat sdf = new SimpleDateFormat( "yyyy-MM-dd HH:mm:ss" );
        return sdf.format( cal.getTime() );
    }

    /**
     *  Gets current time stamp -- time with milliseconds
     */
    public static String nowMillis ()
    {
        Calendar cal = Calendar.getInstance ();
        SimpleDateFormat sdf = new SimpleDateFormat( "HH:mm:ss.SSS" );
        return sdf.format( cal.getTime() );
    }

    /**
     *  Escapes HTML reserved characters (as we are logging HTML tagged messages)
     *  It also replaces form-feed characters with HTML line-breaks. 
     */
    public static String EscapeHTML( String str )
    {
        return str.replaceAll( "&",  "&amp;"  ) 
                  .replaceAll( "<",  "&lt;"   )
                  .replaceAll( ">",  "&gt;"   ) 
                  .replaceAll( "\"", "&quot;" )
                  .replaceAll( "\f", "<br/>" );
    }
}
