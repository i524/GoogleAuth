package com.warrenstrange.googleauth;

import org.apache.commons.codec.binary.Base32;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class implements the functionality described in RFC 6238 (TOTP: Time
 * based one-time password algorithm) and has been tested again Google's
 * implementation of such algorithm in its Google Authenticator application.
 * <p/>
 * This class lets users create a new 16-bit base32-encoded secret key with
 * the validation code calculated at time=0 (the UNIX epoch) and the URL of a
 * Google-provided QR barcode to let an user load the generated information into
 * Google Authenticator.
 * <p/>
 * This class doesn't store in any way either the generated keys nor the keys
 * passed during the authorization process.
 * <p/>
 * Java Server side class for Google Authenticator's TOTP generator was inspired
 * by an author's blog post.
 *
 * @version 1.0
 * @see <a href="http://thegreyblog.blogspot.com/2011/12/google-authenticator-using-it-in-your.html" />
 * @see <a href="http://code.google.com/p/google-authenticator" />
 * @see <a href="http://tools.ietf.org/id/draft-mraihi-totp-timebased-06.txt" />
 * @since 1.0
 */
public final class GoogleAuthenticator {

    /**
     * The logger for this class.
     */
    private static final Logger LOGGER =
            Logger.getLogger(GoogleAuthenticator.class.getName());

    /**
     * The number of bits of a secret key in binary form. Since the Base32
     * encoding with 8 bit characters introduces an 160% overhead, we just need
     * 80 bits (8 bytes) to generate a 16 bytes Base32-encoded secret key.
     */
    private static final int SECRET_BITS = 80;

    /**
     * Number of scratch codes to generate during the key generation.
     * We are using Google's default of providing 5 scratch codes.
     */
    private static final int SCRATCH_CODES = 5;

    /**
     * Length in bytes of each scratch code. We're using Google's default of
     * using 4 bytes per scratch code.
     */
    private static final int BYTES_PER_SCRATCH_CODE = 4;

    /**
     * The size of the seed which is fed to the SecureRandom instance, in bytes.
     */
    private static final int SEED_SIZE = 128;

    /**
     * The SecureRandom algorithm to use.
     *
     * @see java.security.SecureRandom#getInstance(String)
     */
    private static final String RANDOM_NUMBER_ALGORITHM = "SHA1PRNG";

    /**
     * Minimum validation window size.
     */
    public static final int MIN_WINDOW = 1;

    /**
     * Maximum validation window size.
     */
    public static final int MAX_WINDOW = 17;

    /**
     * The initial windowSize used when validating the codes. We are using
     * Google's default behaviour of using a window size equal to 3. The maximum
     * window size is 17.
     */
    private AtomicInteger windowSize = new AtomicInteger(3);

    /**
     * The internal SecureRandom instance used by this class. Since as of Java 7
     * Random instances are required to be thread-safe, no synchronisation is
     * required in the methods of this class using this instance. Thread-safety
     * of this class was a de-facto standard in previous versions of Java so
     * that it is expected to work correctly in previous versions of the Java
     * platform as well.
     */
    private SecureRandom secureRandom;

    /**
     * Cryptographic hash function used to calculate the HMAC (Hash-based
     * Message Authentication Code). This implementation uses the SHA1 hash
     * function.
     */
    public static final String HMAC_HASH_FUNCTION = "HmacSHA1";

    /**
     * Modulus of the secret key.
     */
    public static final int SECRET_KEY_MODULE = 1000 * 1000;

    /**
     * The number of seconds a key is valid.
     */
    public static final long KEY_VALIDATION_INTERVAL_MS =
            TimeUnit.SECONDS.toMillis(30);

    public GoogleAuthenticator() {

        try {
            secureRandom = SecureRandom.getInstance(RANDOM_NUMBER_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new GoogleAuthenticatorException(
                    String.format(
                            "Could not initialise SecureRandom " +
                                    "with the specified algorithm: %s",
                            RANDOM_NUMBER_ALGORITHM), e);
        }

        reSeed();
    }

    public void reSeed() {
        secureRandom.setSeed(secureRandom.generateSeed(SEED_SIZE));
    }

    /**
     * Generate a random secret key. This must be saved by the server and
     * associated with the users account to verify the code displayed by
     * Google Authenticator.
     * <p/>
     * The user must register this secret on their device.
     *
     * @return secret key
     */
    public GoogleAuthenticatorKey generateSecretKey() {

        // Allocating a buffer sufficiently large to hold the bytes required by
        // the secret key and the scratch codes.
        byte[] buffer =
                new byte[SECRET_BITS / 8 + SCRATCH_CODES * BYTES_PER_SCRATCH_CODE];

        secureRandom.nextBytes(buffer);

        // Extracting the bytes making up the secret key.
        byte[] secretKey = Arrays.copyOf(buffer, SECRET_BITS / 8);

        Base32 codec = new Base32();
        byte[] encodedKey = codec.encode(secretKey);

        // Creating a string with the Base32 encoded bytes.
        final String generatedKey = new String(encodedKey);

        // Generating the verification code at time = 0.
        int generateCode;

        try {
            generateCode = calculateCode(secretKey, 0);
        } catch (NoSuchAlgorithmException ex) {
            // Logging the exception.
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);

            // We're not disclosing internal error details to our clients.
            throw new GoogleAuthenticatorException("The operation cannot be "
                    + "performed now.");
        } catch (InvalidKeyException ex) {
            // Logging the exception
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);

            // We're not disclosing internal error details to our clients.
            throw new GoogleAuthenticatorException("The operation cannot be "
                    + "performed now.");
        }

        return new GoogleAuthenticatorKey(generatedKey, generateCode);
    }

    /**
     * Calculates the verification code of the provided key at the specified
     * instant of time using the algorithm specified in RFC 6238.
     *
     * @param key the secret key in binary format.
     * @param tm  the instant of time.
     * @return the validation code for the provided key at the specified instant
     * of time.
     * @throws NoSuchAlgorithmException if the algorithm using during the
     *                                  validation process (HmacSHA1) is not
     *                                  available.
     * @throws InvalidKeyException      if the secret key specification is
     *                                  invalid.
     */
    private static int calculateCode(byte[] key, long tm)
            throws NoSuchAlgorithmException, InvalidKeyException {
        // Allocating an array of bytes to represent the specified instant
        // of time.
        byte[] data = new byte[8];
        long value = tm;

        // Converting the instant of time from the long representation to an
        // array of bytes.
        for (int i = 8; i-- > 0; value >>>= 8) {
            data[i] = (byte) value;
        }

        // Building the secret key specification for the HmacSHA1 algorithm.
        SecretKeySpec signKey = new SecretKeySpec(key, HMAC_HASH_FUNCTION);

        // Getting an HmacSHA1 algorithm implementation from the JCE.
        Mac mac = Mac.getInstance(HMAC_HASH_FUNCTION);

        // Initializing the MAC algorithm.
        mac.init(signKey);

        // Processing the instant of time and getting the encrypted data.
        byte[] hash = mac.doFinal(data);

        // Building the validation code.
        int offset = hash[20 - 1] & 0xF;
        // We are using a long because Java hasn't got an unsigned integer type.
        long truncatedHash = 0;

        for (int i = 0; i < 4; ++i) {
            //truncatedHash = (truncatedHash * 256) & 0xFFFFFFFF;
            truncatedHash <<= 8;

            // Java bytes are signed but we need an unsigned one:
            // cleaning off all but the LSB.
            truncatedHash |= (hash[offset + i] & 0xFF);
        }

        // Cleaning bits higher than 32nd and calculating the module with the
        // maximum validation code value.
        truncatedHash &= 0x7FFFFFFF;
        truncatedHash %= SECRET_KEY_MODULE;

        // Returning the validation code to the caller.
        return (int) truncatedHash;
    }

    /**
     * Set the default window size used by this instance when an explicit value
     * is not specified. This is an integer value representing the number of 30
     * second windows we check during the validation process, to account for
     * differences between the server and the client clocks.
     * The bigger the window, the more tolerant we are about clock skews.
     *
     * @param s window size - must be >=1 and <=17.  Other values are ignored
     */
    public void setWindowSize(int s) {
        if (s >= MIN_WINDOW && s <= MAX_WINDOW) {
            windowSize = new AtomicInteger(s);
        } else {
            throw new GoogleAuthenticatorException(
                    String.format("Invalid window size: %d", s));
        }
    }

    /**
     * Get the default window size used by this instance when an explicit value
     * is not specified.
     *
     * @return the current window size.
     */
    public int getWindowSize() {
        return windowSize.get();
    }

    /**
     * Checks a verification code against a secret key using the current time.
     * The algorithm also checks in a time window whose size determined by the
     * <code>windowSize</code> property of this class.
     * <p/>
     * We are using Google's default value of 30 seconds for the interval size.
     *
     * @param secret           the Base32 encoded secret key.
     * @param verificationCode the verification code.
     * @return <code>true</code> if the validation code is valid,
     * <code>false</code> otherwise.
     * @throws GoogleAuthenticatorException if a failure occurs during the
     *                                      calculation of the validation code.
     *                                      The only failures that should occur
     *                                      are related with the cryptographic
     *                                      functions provided by the JCE.
     * @see #getWindowSize()
     */
    public boolean authorize(String secret, int verificationCode)
            throws GoogleAuthenticatorException {
        return authorize(secret, verificationCode, this.windowSize.get());
    }

    /**
     * Checks a verification code against a secret key using the current time.
     * The algorithm also checks in a time window whose size is fixed to a value
     * of [-(window - 1)/2, +(window - 1)/2] time intervals. The maximum size of
     * the window is specified by the <code>MAX_WINDOW</code> constant and
     * cannot be overridden.
     * <p/>
     * We are using Google's default value of 30 seconds for the interval size.
     *
     * @param secret           the Base32 encoded secret key.
     * @param verificationCode the verification code.
     * @param window           the window size to use during the validation process.
     * @return <code>true</code> if the validation code is valid,
     * <code>false</code> otherwise.
     * @throws GoogleAuthenticatorException if a failure occurs during the
     *                                      calculation of the validation code.
     *                                      The only failures that should occur
     *                                      are related with the cryptographic
     *                                      functions provided by the JCE.
     * @see GoogleAuthenticator#MAX_WINDOW
     */
    public static boolean authorize(
            String secret,
            int verificationCode,
            int window)
            throws GoogleAuthenticatorException {
        // Checking user input and failing if the secret key was not provided.
        if (secret == null) {
            throw new GoogleAuthenticatorException("Secret cannot be null.");
        }

        // Checking if the verification code is between the legal bounds.
        if (verificationCode <= 0 || verificationCode >= SECRET_KEY_MODULE) {
            return false;
        }

        // Checking if the window size is between the legal bounds.
        if (window < MIN_WINDOW || window > MAX_WINDOW) {
            throw new GoogleAuthenticatorException("Invalid window size.");
        }

        try {
            // Checking the validation code using the current UNIX time.
            return checkCode(
                    secret,
                    verificationCode,
                    new Date().getTime(),
                    window);
        } catch (NoSuchAlgorithmException ex) {
            // Logging the exception.
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
            // We're not disclosing internal error details to our clients.
            throw new UnsupportedOperationException("The operation cannot be "
                    + "performed now.");
        } catch (InvalidKeyException ex) {
            // Logging the exception.
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
            // Informing the user that the provided secret key has been
            // recognized as invalid by the JCE framework.
            throw new UnsupportedOperationException("The operation cannot be "
                    + "performed: the secret key is invalid.");
        }
    }

    /**
     * This method implements the algorithm specified in RFC 6238 to check if a
     * validation code is valid in a given instant of time for the given secret
     * key.
     *
     * @param secret the Base32 encoded secret key.
     * @param code   the code to validate.
     * @param tm     the instant of time to use during the validation process.
     * @param window the window size to use during the validation process.
     * @return <code>true</code> if the validation code is valid,
     * <code>false</code> otherwise.
     * @throws NoSuchAlgorithmException if the algorithm using during the
     *                                  validation process (HmacSHA1) is not available.
     * @throws InvalidKeyException      if the secret key specification is invalid.
     */
    private static boolean checkCode(
            String secret,
            long code,
            long tm,
            int window)
            throws NoSuchAlgorithmException, InvalidKeyException {
        // Decoding the secret key to get its raw byte representation.
        Base32 codec = new Base32();
        byte[] decodedKey = codec.decode(secret);

        // convert unix time into a 30 second "window" as specified by the
        // TOTP specification. Using Google's default interval of 30 seconds.
        final long timeWindow = tm / KEY_VALIDATION_INTERVAL_MS;

        // Calculating the verification code of the given key in each of the
        // time intervals and returning true if the provided code is equal to
        // one of them.
        for (int i = -((window - 1) / 2); i <= window / 2; ++i) {
            // Calculating the verification code for the current time interval.
            long hash = calculateCode(decodedKey, timeWindow + i);

            // Checking if the provided code is equal to the calculated one.
            if (hash == code) {
                // The verification code is valid.
                return true;
            }
        }

        // The verification code is invalid.
        return false;
    }
}
