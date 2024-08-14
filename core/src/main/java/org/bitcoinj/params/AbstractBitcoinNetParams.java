/*
 * Copyright 2013 Google Inc.
 * Copyright 2015 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bitcoinj.params;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import org.bitcoinj.core.Block;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Utils;
import org.bitcoinj.utils.MonetaryFormat;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Stopwatch;

import org.bitcoinj.core.BitcoinSerializer;

import static com.google.common.base.Preconditions.checkState;

/**
 * Parameters for Bitcoin-like networks.
 */
public abstract class AbstractBitcoinNetParams extends NetworkParameters {
    /**
     * Scheme part for Bitcoin URIs.
     */
    public static final String BITCOIN_SCHEME = "ferrite";

    private static final Logger log = LoggerFactory.getLogger(AbstractBitcoinNetParams.class);


    protected int powDGWHeight;
    protected boolean powAllowMinimumDifficulty;
    protected boolean powNoRetargeting;

    public AbstractBitcoinNetParams() {
        super();
    }

    /** 
     * Checks if we are at a difficulty transition point. 
     * @param storedPrev The previous stored block 
     * @return If this is a difficulty transition point 
     */
    protected boolean isDifficultyTransitionPoint(StoredBlock storedPrev) {
        return ((storedPrev.getHeight() + 1) % this.getInterval()) == 0;
    }

    protected boolean isDifficultyTransitionPoint(int height) {
        return height >= powDGWHeight ? true :
                ((height + 1) % this.getInterval()) == 0;
    }

    // DAA selector function
    @Override
    public void checkDifficultyTransitions(final StoredBlock storedPrev, final Block nextBlock,
                                           final BlockStore blockStore) throws VerificationException, BlockStoreException {
        int height = storedPrev.getHeight() + 1;
        if(height >= powDGWHeight) {
            DarkGravityWave(storedPrev, nextBlock, blockStore);
        } else {
            checkDifficultyTransitions_btc(storedPrev, nextBlock, blockStore);
        }
    }

    public void checkDifficultyTransitions_btc(final StoredBlock storedPrev, final Block nextBlock,
    	final BlockStore blockStore) throws VerificationException, BlockStoreException {
        Block prev = storedPrev.getHeader();

        // Is this supposed to be a difficulty transition point?
        if (!isDifficultyTransitionPoint(storedPrev)) {

            // No ... so check the difficulty didn't actually change.
            if (nextBlock.getDifficultyTarget() != prev.getDifficultyTarget())
                throw new VerificationException("Unexpected change in difficulty at height " + storedPrev.getHeight() +
                        ": " + Long.toHexString(nextBlock.getDifficultyTarget()) + " vs " +
                        Long.toHexString(prev.getDifficultyTarget()));
            return;
        }

        // We need to find a block far back in the chain. It's OK that this is expensive because it only occurs every
        // two weeks after the initial block chain download.
        final Stopwatch watch = Stopwatch.createStarted();
        StoredBlock cursor = blockStore.get(prev.getHash());
        for (int i = 0; i < this.getInterval() - 1; i++) {
            if (cursor == null) {
                // This should never happen. If it does, it means we are following an incorrect or busted chain.
                throw new VerificationException(
                        "Difficulty transition point but we did not find a way back to the genesis block.");
            }
            cursor = blockStore.get(cursor.getHeader().getPrevBlockHash());
        }
        watch.stop();
        if (watch.elapsed(TimeUnit.MILLISECONDS) > 50)
            log.info("Difficulty transition traversal took {}", watch);

        Block blockIntervalAgo = cursor.getHeader();
        int timespan = (int) (prev.getTimeSeconds() - blockIntervalAgo.getTimeSeconds());
        // Limit the adjustment step.
        final int targetTimespan = this.getTargetTimespan();
        if (timespan < targetTimespan / 4)
            timespan = targetTimespan / 4;
        if (timespan > targetTimespan * 4)
            timespan = targetTimespan * 4;

        BigInteger newTarget = Utils.decodeCompactBits(prev.getDifficultyTarget());
        newTarget = newTarget.multiply(BigInteger.valueOf(timespan));
        newTarget = newTarget.divide(BigInteger.valueOf(targetTimespan));

        if (newTarget.compareTo(this.getMaxTarget()) > 0) {
            log.info("Difficulty hit proof of work limit: {}", newTarget.toString(16));
            newTarget = this.getMaxTarget();
        }

        int accuracyBytes = (int) (nextBlock.getDifficultyTarget() >>> 24) - 3;
        long receivedTargetCompact = nextBlock.getDifficultyTarget();

        // The calculated difficulty is to a higher precision than received, so reduce here.
        BigInteger mask = BigInteger.valueOf(0xFFFFFFL).shiftLeft(accuracyBytes * 8);
        newTarget = newTarget.and(mask);
        long newTargetCompact = Utils.encodeCompactBits(newTarget);

        if (newTargetCompact != receivedTargetCompact)
            throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                    Long.toHexString(newTargetCompact) + " vs " + Long.toHexString(receivedTargetCompact));
    }

    protected long calculateNextDifficulty(StoredBlock storedBlock, Block nextBlock, BigInteger newTarget) {
        if (newTarget.compareTo(this.getMaxTarget()) > 0) {
            newTarget = this.getMaxTarget();
        }

        int accuracyBytes = (int) (nextBlock.getDifficultyTarget() >>> 24) - 3;

        // The calculated difficulty is to a higher precision than received, so reduce here.
        BigInteger mask = BigInteger.valueOf(0xFFFFFFL).shiftLeft(accuracyBytes * 8);
        newTarget = newTarget.and(mask);
        return Utils.encodeCompactBits(newTarget);
    }

    protected void verifyDifficulty(StoredBlock storedPrev, Block nextBlock, BigInteger newTarget) throws VerificationException {
        long newTargetCompact = calculateNextDifficulty(storedPrev, nextBlock, newTarget);
        long receivedTargetCompact = nextBlock.getDifficultyTarget();

        if (newTargetCompact != receivedTargetCompact)
            throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                    Long.toHexString(newTargetCompact) + " vs " + Long.toHexString(receivedTargetCompact));
    }

    public void DarkGravityWave(StoredBlock storedPrev, Block nextBlock,
                                  final BlockStore blockStore) throws VerificationException {
        /* current difficulty formula, darkcoin - DarkGravity v3, written by Evan Duffield - evan@darkcoin.io */
        long pastBlocks = 24;

        if (storedPrev == null || storedPrev.getHeight() == 0 || storedPrev.getHeight() < pastBlocks) {
            verifyDifficulty(storedPrev, nextBlock, getMaxTarget());
            return;
        }

        if(powAllowMinimumDifficulty)
        {
            // recent block is more than 2 hours old
            if (nextBlock.getTimeSeconds() > storedPrev.getHeader().getTimeSeconds() + 2 * 60 * 60) {
                verifyDifficulty(storedPrev, nextBlock, getMaxTarget());
                return;
            }
            // recent block is more than 10 minutes old
            if (nextBlock.getTimeSeconds() > storedPrev.getHeader().getTimeSeconds() + NetworkParameters.TARGET_SPACING*4) {
                BigInteger newTarget = storedPrev.getHeader().getDifficultyTargetAsInteger().multiply(BigInteger.valueOf(10));
                verifyDifficulty(storedPrev, nextBlock, newTarget);
                return;
            }
        }

        StoredBlock cursor = storedPrev;
        BigInteger pastTargetAverage = BigInteger.ZERO;
        for(int countBlocks = 1; countBlocks <= pastBlocks; countBlocks++) {
            BigInteger target = cursor.getHeader().getDifficultyTargetAsInteger();
            if(countBlocks == 1) {
                pastTargetAverage = target;
            } else {
                pastTargetAverage = pastTargetAverage.multiply(BigInteger.valueOf(countBlocks)).add(target).divide(BigInteger.valueOf(countBlocks+1));
            }
            if(countBlocks != pastBlocks) {
                try {
                    cursor = cursor.getPrev(blockStore);
                    if(cursor == null) {
                        //when using checkpoints, the previous block will not exist until 24 blocks are in the store.
                        return;
                    }
                } catch (BlockStoreException x) {
                    //when using checkpoints, the previous block will not exist until 24 blocks are in the store.
                    return;
                }
            }
        }


        BigInteger newTarget = pastTargetAverage;

        long timespan = storedPrev.getHeader().getTimeSeconds() - cursor.getHeader().getTimeSeconds();
        long targetTimespan = pastBlocks*TARGET_SPACING;

        if (timespan < targetTimespan/3)
            timespan = targetTimespan/3;
        if (timespan > targetTimespan*3)
            timespan = targetTimespan*3;

        // Retarget
        newTarget = newTarget.multiply(BigInteger.valueOf(timespan));
        newTarget = newTarget.divide(BigInteger.valueOf(targetTimespan));
        verifyDifficulty(storedPrev, nextBlock, newTarget);

    }

    @Override
    public Coin getMaxMoney() {
        return MAX_MONEY;
    }

    @Override
    public Coin getMinNonDustOutput() {
        return Transaction.MIN_NONDUST_OUTPUT;
    }

    @Override
    public MonetaryFormat getMonetaryFormat() {
        return new MonetaryFormat();
    }

    @Override
    public int getProtocolVersionNum(final ProtocolVersion version) {
        return version.getBitcoinProtocolVersion();
    }

    @Override
    public BitcoinSerializer getSerializer(boolean parseRetain) {
        return new BitcoinSerializer(this, parseRetain);
    }

    @Override
    public String getUriScheme() {
        return BITCOIN_SCHEME;
    }

    @Override
    public boolean hasMaxMoney() {
        return true;
    }
}
