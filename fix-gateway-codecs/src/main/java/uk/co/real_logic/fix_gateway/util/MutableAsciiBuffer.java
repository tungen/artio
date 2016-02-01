/*
 * Copyright 2015 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.fix_gateway.util;

import uk.co.real_logic.agrona.DirectBuffer;
import uk.co.real_logic.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.fix_gateway.fields.*;

import java.nio.ByteBuffer;

import static java.nio.charset.StandardCharsets.US_ASCII;

public final class MutableAsciiBuffer extends UnsafeBuffer implements AsciiBuffer
{
    public static final int LONGEST_INT_LENGTH = String.valueOf(Integer.MIN_VALUE).length();
    public static final int LONGEST_LONG_LENGTH = String.valueOf(Long.MIN_VALUE).length();
    public static final int LONGEST_FLOAT_LENGTH = LONGEST_LONG_LENGTH + 3;

    private static final byte ZERO = '0';
    private static final byte SEPARATOR = (byte)'\001';
    private static final byte DOT = (byte)'.';

    private static final byte Y = (byte)'Y';
    private static final byte N = (byte)'N';
    public static final int SIZE_OF_DOT = 1;

    private static final int[] INT_ROUNDS =
    {
        9, 99, 999, 9999, 99999, 999999, 9999999, 99999999, 999999999, Integer.MAX_VALUE
    };

    private static final long[] LONG_ROUNDS =
    {
        9L, 99L, 999L, 9999L, 99999L, 999999L, 9999999L, 99999999L, 999999999L,
        9_999999999L, 99_999999999L, 999_999999999L, 9999_999999999L,
        99999_999999999L, 999999_999999999L, 9999999_999999999L, 99999999_999999999L,
        999999999_999999999L, Long.MAX_VALUE
    };

    private static final byte[] MIN_INTEGER_VALUE = String.valueOf(Integer.MIN_VALUE).getBytes(US_ASCII);
    private static final byte[] MIN_LONG_VALUE = String.valueOf(Long.MIN_VALUE).getBytes(US_ASCII);

    public MutableAsciiBuffer()
    {
        super(0, 0);
    }

    public MutableAsciiBuffer(final byte[] buffer)
    {
        super(buffer);
    }

    public MutableAsciiBuffer(final byte[] buffer, final int offset, final int length)
    {
        super(buffer, offset, length);
    }

    public MutableAsciiBuffer(final ByteBuffer buffer)
    {
        super(buffer);
    }

    public MutableAsciiBuffer(final ByteBuffer buffer, final int offset, final int length)
    {
        super(buffer, offset, length);
    }

    public MutableAsciiBuffer(final DirectBuffer buffer)
    {
        super(buffer);
    }

    public MutableAsciiBuffer(final DirectBuffer buffer, final int offset, final int length)
    {
        super(buffer, offset, length);
    }

    public MutableAsciiBuffer(final long address, final int length)
    {
        super(address, length);
    }

    public int getNatural(final int startInclusive, final int endExclusive)
    {
        int tally = 0;
        for (int index = startInclusive; index < endExclusive; index++)
        {
            tally = (tally * 10) + getDigit(index);
        }

        return tally;
    }

    public int getInt(int startInclusive, final int endExclusive)
    {
        final byte first = getByte(startInclusive);
        if (first == NEGATIVE)
        {
            startInclusive++;
        }

        int tally = 0;
        for (int index = startInclusive; index < endExclusive; index++)
        {
            tally = (tally * 10) + getDigit(index);
        }

        if (first == NEGATIVE)
        {
            tally *= -1;
        }

        return tally;
    }

    public int getDigit(final int index)
    {
        final byte value = getByte(index);
        return getDigit(index, value);
    }

    public boolean isDigit(final int index)
    {
        final byte value = getByte(index);
        return value >= 0x30 && value <= 0x39;
    }

    private int getDigit(final int index, final byte value)
    {
        if (value < 0x30 || value > 0x39)
        {
            throw new IllegalArgumentException("'" + ((char)value) + "' isn't a valid digit @ " + index);
        }

        return value - 0x30;
    }

    public char getChar(final int index)
    {
        return (char)getByte(index);
    }

    public boolean getBoolean(final int index)
    {
        return YES == getByte(index);
    }

    public byte[] getBytes(final byte[] oldBuffer, final int offset, final int length)
    {
        final byte[] resultBuffer = oldBuffer.length < length ? new byte[length] : oldBuffer;
        getBytes(offset, resultBuffer, 0, length);
        return resultBuffer;
    }

    public char[] getChars(final char[] oldBuffer, final int offset, final int length)
    {
        final char[] resultBuffer = oldBuffer.length < length ? new char[length] : oldBuffer;
        for (int i = 0; i < length; i++)
        {
            resultBuffer[i] = getChar(i + offset);
        }
        return resultBuffer;
    }

    /**
     * Not at all a performant conversion: don't use this on a critical application path.
     *
     * @param offset
     * @param length
     * @return a String
     */
    public String getAscii(final int offset, final int length)
    {
        final byte[] buff = new byte[length];
        getBytes(offset, buff);
        return new String(buff, 0, length, US_ASCII);
    }

    public int getMessageType(final int offset, final int length)
    {
        // message types can only be 1 or 2 bytes in size
        if (length == 1)
        {
            return getByte(offset);
        }
        else
        {
            return getShort(offset);
        }
    }

    public DecimalFloat getFloat(final DecimalFloat number, int offset, int length)
    {
        // Throw away trailing zeros
        int end = offset + length;
        for (int index = end - 1; isDispensableCharacter(index) && index > offset; index--)
        {
            end--;
        }

        // Is it negative?
        final boolean negative = getByte(offset) == '-';
        if (negative)
        {
            offset++;
            length--;
        }

        // Throw away leading zeros
        for (int index = offset; isDispensableCharacter(index) && index < end; index++)
        {
            offset++;
        }

        int scale = 0;
        long value = 0;
        for (int index = offset; index < end; index++)
        {
            final byte byteValue = getByte(index);
            if (byteValue == '.')
            {
                // number of digits after the dot
                scale = end - (index + 1);
            }
            else
            {
                final int digit = getDigit(index);
                value = value * 10 + digit;
            }
        }

        number.value(negative ? -1 * value : value);
        number.scale(scale);
        return number;
    }

    public int getLocalMktDate(final int offset, final int length)
    {
        return LocalMktDateDecoder.decode(this, offset, length);
    }

    public long getUtcTimestamp(final int offset, final int length)
    {
        return UtcTimestampDecoder.decode(this, offset, length);
    }

    public long getUtcTimeOnly(final int offset, final int length)
    {
        return UtcTimeOnlyDecoder.decode(this, offset, length);
    }

    public int getUtcDateOnly(final int offset)
    {
        return UtcDateOnlyDecoder.decode(this, offset);
    }

    public int scanBack(final int startInclusive, final int endExclusive, final char terminatingCharacter)
    {
        return scanBack(startInclusive, endExclusive, (byte)terminatingCharacter);
    }

    public int scanBack(final int startInclusive, final int endExclusive, final byte terminator)
    {
        for (int index = startInclusive; index >= endExclusive; index--)
        {
            final byte value = getByte(index);
            if (value == terminator)
            {
                return index;
            }
        }

        return UNKNOWN_INDEX;
    }

    public int scan(final int startInclusive, final int endInclusive, final char terminatingCharacter)
    {
        return scan(startInclusive, endInclusive, (byte)terminatingCharacter);
    }

    public int scan(final int startInclusive, final int endInclusive, final byte terminator)
    {
        int indexValue = UNKNOWN_INDEX;
        for (int i = startInclusive; i <= endInclusive; i++)
        {
            final byte value = getByte(i);
            if (value == terminator)
            {
                indexValue = i;
                break;
            }
        }

        return indexValue;
    }

    public int computeChecksum(final int offset, final int end)
    {
        int total = 0;
        for (int index = offset; index < end; index++)
        {
            total += (int) getByte(index);
        }

        return total % 256;
    }

    private boolean isDispensableCharacter(final int index)
    {
        final byte character = getByte(index);
        return character == '0' || character == ' ';
    }

    public int putAscii(final int index, final String string)
    {
        final byte[] bytes = string.getBytes(US_ASCII);
        putBytes(index, bytes);

        return bytes.length;
    }

    public void putSeparator(final int index)
    {
        putByte(index, SEPARATOR);
    }

    public int putAsciiBoolean(final int offset, final boolean value)
    {
        putByte(offset, value ? Y : N);
        return 1;
    }

    public void putNatural(final int offset, final int length, final int value)
    {
        final int end = offset + length;
        int remainder = value;
        for (int index = end - 1; index >= offset; index--)
        {
            final int digit = remainder % 10;
            remainder = remainder / 10;
            putByte(index, (byte)(ZERO + digit));
        }

        if (remainder != 0)
        {
            throw new IllegalArgumentException(String.format("Cannot write %d in %d bytes", value, length));
        }
    }

    /**
     * Puts an int into the buffer
     *
     * @param offset the offset at which to put the int
     * @param value  the int to write
     * @return the number of bytes that the int took up encoded
     */
    public int putAsciiInt(final int offset, final int value)
    {
        if (zero(offset, value))
        {
            return 1;
        }

        if (value == Integer.MIN_VALUE)
        {
            putBytes(offset, MIN_INTEGER_VALUE);
            return MIN_INTEGER_VALUE.length;
        }

        int start = offset;
        int quotient = value;
        int length = 1;
        if (value < 0)
        {
            putChar(offset, '-');
            start++;
            length++;
            quotient = -quotient;
        }

        int i = endOffset(quotient);
        length += i;

        while (i >= 0)
        {
            final int remainder = quotient % 10;
            quotient = quotient / 10;
            putByte(i + start, (byte)(ZERO + remainder));
            i--;
        }

        return length;
    }

    private static int endOffset(final int value)
    {
        for (int i = 0; true; i++)
        {
            if (value <= INT_ROUNDS[i])
            {
                return i;
            }
        }
    }

    public int putAsciiLong(final int offset, final long value)
    {
        if (zero(offset, value))
        {
            return 1;
        }

        if (value == Long.MIN_VALUE)
        {
            putBytes(offset, MIN_LONG_VALUE);
            return MIN_LONG_VALUE.length;
        }

        int start = offset;
        long quotient = value;
        int length = 1;
        if (value < 0)
        {
            putChar(offset, '-');
            start++;
            length++;
            quotient = -quotient;
        }

        int i = endOffset(quotient);
        length += i;

        while (i >= 0)
        {
            final long remainder = quotient % 10;
            quotient = quotient / 10;
            putByte(i + start, (byte)(ZERO + remainder));
            i--;
        }

        return length;
    }

    public int putAsciiChar(final int index, final char value)
    {
        putByte(index, (byte) value);
        return 1;
    }

    private static int endOffset(final long value)
    {
        for (int i = 0; true; i++)
        {
            if (value <= LONG_ROUNDS[i])
            {
                return i;
            }
        }
    }

    public int putAsciiFloat(final int offset, final DecimalFloat price)
    {
        final long value = price.value();
        final int scale = price.scale();
        if (zero(offset, value))
        {
            return 1;
        }

        final long remainder = calculateRemainderAndPutMinus(offset, value);
        final int minusAdj = value < 0 ? 1 : 0;
        final int start = offset + minusAdj;

        // Encode the value into a tmp space, leaving the longest possible space required
        final int tmpEnd = start + LONGEST_LONG_LENGTH;
        final int tmpStart = putLong(remainder, tmpEnd) + 1;
        final int length = tmpEnd - tmpStart + SIZE_OF_DOT;

        // Move the value to the beginning once you've encoded it
        if (scale > 0)
        {
            final int end = start + length;
            final int split = end - scale;
            final int digitsBeforeDot = length - scale;
            putBytes(start, this, tmpStart, digitsBeforeDot);
            putByte(split, DOT);
            putBytes(split + 1, this, tmpStart + digitsBeforeDot, scale);
            return length + SIZE_OF_DOT + minusAdj;
        }
        else
        {
            putBytes(start, this, tmpStart, length);
            return length + minusAdj;
        }
    }

    private boolean zero(final int offset, final long value)
    {
        if (value == 0)
        {
            putByte(offset, ZERO);
            return true;
        }
        return false;
    }

    private long calculateRemainderAndPutMinus(final int offset, final long value)
    {
        if (value < 0)
        {
            putChar(offset, '-');
            return value;
        }
        else
        {
            // Deal with negatives to avoid overflow for Integer.MAX_VALUE
            return -1L * value;
        }
    }

    private int putLong(long remainder, final int end)
    {
        int index = end;
        while (remainder < 0)
        {
            final long digit = remainder % 10;
            remainder = remainder / 10;
            putByte(index, (byte)(ZERO + (-1L * digit)));
            index--;
        }

        return index;
    }
}