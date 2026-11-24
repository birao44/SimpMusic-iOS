package com.maxrave.data.repository

/**
 * True where streams are played by AVPlayer (iOS): AAC/MP4 or HLS only, and no `range=` parameter on
 * the URL. See StreamRepositoryImpl.getStream.
 */
expect val streamsForAvPlayer: Boolean
