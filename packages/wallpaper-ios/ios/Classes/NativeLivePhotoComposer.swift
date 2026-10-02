import AVFoundation
import CoreImage
import CoreMedia
import ImageIO
import UniformTypeIdentifiers
import VideoToolbox

struct NativeLivePhotoResources {
  let photoURL: URL
  let videoURL: URL
}

enum NativeLivePhotoComposer {
  private enum CompositionError: Error {
    case missingVideoTrack
    case readerCreationFailed
    case writerCreationFailed
    case pixelBufferCreationFailed
    case frameCountInvalid
    case invalidCanvasSize
    case imageCreationFailed
    case sampleTransferFailed
  }

  static func create(
    from sourceURL: URL,
    metadataTemplateURL: URL,
    targetAspectRatio: CGFloat,
    deviceModel: String,
    systemVersion: String,
    creationDate: String,
    directory: URL
  ) async throws -> NativeLivePhotoResources {
    let identifier = UUID().uuidString
    let canvasURL = directory.appendingPathComponent("canvas.mov")
    let photoURL = directory.appendingPathComponent("photo.heic")
    let pairedVideoURL = directory.appendingPathComponent("paired.mov")

    try await createCanvasVideo(
      from: sourceURL,
      targetAspectRatio: targetAspectRatio,
      outputURL: canvasURL
    )
    try await createImage(from: canvasURL, identifier: identifier, outputURL: photoURL)
    try await createPairedVideo(
      from: canvasURL,
      metadataTemplateURL: metadataTemplateURL,
      identifier: identifier,
      deviceModel: deviceModel,
      systemVersion: systemVersion,
      creationDate: creationDate,
      outputURL: pairedVideoURL
    )
    return NativeLivePhotoResources(photoURL: photoURL, videoURL: pairedVideoURL)
  }

  private static func createCanvasVideo(
    from sourceURL: URL,
    targetAspectRatio: CGFloat,
    outputURL: URL
  ) async throws {
    let asset = AVURLAsset(url: sourceURL)
    guard let videoTrack = try await asset.loadTracks(withMediaType: .video).first else {
      throw CompositionError.missingVideoTrack
    }
    let preferredTransform = try await videoTrack.load(.preferredTransform)
    let naturalSize = try await videoTrack.load(.naturalSize)
    let displayBounds = CGRect(origin: .zero, size: naturalSize).applying(preferredTransform)
    let sourceDisplaySize = CGSize(
      width: abs(displayBounds.width),
      height: abs(displayBounds.height)
    )
    let targetSize = try highQualityCanvasSize(
      sourceSize: sourceDisplaySize,
      targetAspectRatio: targetAspectRatio
    )
    guard let reader = try? AVAssetReader(asset: asset),
          let writer = try? AVAssetWriter(outputURL: outputURL, fileType: .mov) else {
      throw CompositionError.writerCreationFailed
    }

    let readerOutput = AVAssetReaderTrackOutput(
      track: videoTrack,
      outputSettings: [
        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
      ]
    )
    readerOutput.alwaysCopiesSampleData = false
    guard reader.canAdd(readerOutput) else { throw CompositionError.readerCreationFailed }
    reader.add(readerOutput)

    let width = Int(targetSize.width)
    let height = Int(targetSize.height)
    let averageBitRate = min(
      100_000_000,
      max(40_000_000, Int(Double(width * height) * 60.0 * 0.30))
    )
    let compression: [String: Any] = [
      AVVideoAverageBitRateKey: averageBitRate,
      AVVideoExpectedSourceFrameRateKey: 60,
      AVVideoMaxKeyFrameIntervalKey: 60,
      AVVideoAllowFrameReorderingKey: true,
      AVVideoProfileLevelKey: kVTProfileLevel_HEVC_Main_AutoLevel,
    ]
    let writerInput = AVAssetWriterInput(
      mediaType: .video,
      outputSettings: [
        AVVideoCodecKey: AVVideoCodecType.hevc,
        AVVideoWidthKey: width,
        AVVideoHeightKey: height,
        AVVideoCompressionPropertiesKey: compression,
      ]
    )
    writerInput.expectsMediaDataInRealTime = false
    let adaptor = AVAssetWriterInputPixelBufferAdaptor(
      assetWriterInput: writerInput,
      sourcePixelBufferAttributes: [
        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
        kCVPixelBufferWidthKey as String: width,
        kCVPixelBufferHeightKey as String: height,
        kCVPixelBufferIOSurfacePropertiesKey as String: [:],
      ]
    )
    guard writer.canAdd(writerInput) else { throw CompositionError.writerCreationFailed }
    writer.add(writerInput)
    guard writer.startWriting(), reader.startReading() else {
      throw CompositionError.writerCreationFailed
    }
    writer.startSession(atSourceTime: .zero)

    let context = CIContext(options: [.cacheIntermediates: false])
    let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)
    var frameIndex: Int64 = 0
    while frameIndex < 60, let sample = readerOutput.copyNextSampleBuffer() {
      try Task.checkCancellation()
      guard let sourceBuffer = CMSampleBufferGetImageBuffer(sample) else {
        throw CompositionError.sampleTransferFailed
      }
      while !writerInput.isReadyForMoreMediaData {
        try Task.checkCancellation()
        try await Task.sleep(nanoseconds: 1_000_000)
      }
      guard let pool = adaptor.pixelBufferPool else {
        throw CompositionError.pixelBufferCreationFailed
      }
      var destination: CVPixelBuffer?
      guard CVPixelBufferPoolCreatePixelBuffer(nil, pool, &destination) == kCVReturnSuccess,
            let destination else {
        throw CompositionError.pixelBufferCreationFailed
      }

      let source = normalizedImage(CIImage(cvPixelBuffer: sourceBuffer), transform: preferredTransform)
      let canvas = composedCanvas(source: source, targetSize: targetSize)
      context.render(
        canvas,
        to: destination,
        bounds: CGRect(origin: .zero, size: targetSize),
        colorSpace: colorSpace
      )
      let presentationTime = CMTime(value: frameIndex, timescale: 60)
      guard adaptor.append(destination, withPresentationTime: presentationTime) else {
        throw writer.error ?? CompositionError.sampleTransferFailed
      }
      frameIndex += 1
    }
    guard frameIndex == 60 else { throw CompositionError.frameCountInvalid }
    // The protected source can be longer than the one-second Live Photo output.
    // Keep the creator's first 60 displayed frames in order and ignore everything after them.
    reader.cancelReading()

    writerInput.markAsFinished()
    writer.endSession(atSourceTime: CMTime(value: 60, timescale: 60))
    await writer.finishWriting()
    guard writer.status == .completed else {
      throw writer.error ?? CompositionError.writerCreationFailed
    }
  }

  private static func highQualityCanvasSize(
    sourceSize: CGSize,
    targetAspectRatio: CGFloat
  ) throws -> CGSize {
    guard sourceSize.width.isFinite, sourceSize.height.isFinite,
          sourceSize.width > 0, sourceSize.height > 0,
          targetAspectRatio.isFinite, targetAspectRatio > 0, targetAspectRatio <= 1 else {
      throw CompositionError.invalidCanvasSize
    }

    let sourceAspectRatio = sourceSize.width / sourceSize.height
    let minimumPhoneAspectRatio = CGFloat(9.0 / 16.0)
    var width: CGFloat
    var height: CGFloat
    if targetAspectRatio <= minimumPhoneAspectRatio,
       sourceAspectRatio < targetAspectRatio {
      width = sourceSize.width
      height = width / targetAspectRatio
    } else if sourceAspectRatio < targetAspectRatio {
      height = sourceSize.height
      width = height * targetAspectRatio
    } else {
      width = sourceSize.width
      height = width / targetAspectRatio
    }

    let maximumDimension: CGFloat = 4096
    let scale = min(1, maximumDimension / max(width, height))
    width *= scale
    height *= scale

    let evenWidth = max(2, Int(floor(width / 2)) * 2)
    let evenHeight = max(2, Int(floor(height / 2)) * 2)
    guard evenWidth <= Int(maximumDimension), evenHeight <= Int(maximumDimension) else {
      throw CompositionError.invalidCanvasSize
    }
    return CGSize(width: CGFloat(evenWidth), height: CGFloat(evenHeight))
  }

  private static func normalizedImage(_ image: CIImage, transform: CGAffineTransform) -> CIImage {
    let transformed = transform.isIdentity ? image : image.transformed(by: transform)
    let extent = transformed.extent
    return transformed.transformed(
      by: CGAffineTransform(translationX: -extent.minX, y: -extent.minY)
    )
  }

  private static func composedCanvas(source: CIImage, targetSize: CGSize) -> CIImage {
    let target = CGRect(origin: .zero, size: targetSize)
    let sourceSize = source.extent.size

    let fillScale = max(targetSize.width / sourceSize.width, targetSize.height / sourceSize.height)
    let filled = centered(source: source, scale: fillScale, targetSize: targetSize)
    let background = filled
      .clampedToExtent()
      .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: 36.0])
      .cropped(to: target)

    let fitScale = min(targetSize.width / sourceSize.width, targetSize.height / sourceSize.height)
    let foreground = centered(source: source, scale: fitScale, targetSize: targetSize)
    return foreground.composited(over: background).cropped(to: target)
  }

  private static func centered(source: CIImage, scale: CGFloat, targetSize: CGSize) -> CIImage {
    let scaled = source.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
    return scaled.transformed(
      by: CGAffineTransform(
        translationX: (targetSize.width - scaled.extent.width) / 2 - scaled.extent.minX,
        y: (targetSize.height - scaled.extent.height) / 2 - scaled.extent.minY
      )
    )
  }

  private static func createImage(
    from videoURL: URL,
    identifier: String,
    outputURL: URL
  ) async throws {
    let generator = AVAssetImageGenerator(asset: AVURLAsset(url: videoURL))
    generator.appliesPreferredTrackTransform = true
    generator.requestedTimeToleranceBefore = .zero
    generator.requestedTimeToleranceAfter = .zero
    var actualTime = CMTime.zero
    let image = try generator.copyCGImage(
      at: .zero,
      actualTime: &actualTime
    )
    guard let destination = CGImageDestinationCreateWithURL(
      outputURL as CFURL,
      UTType.heic.identifier as CFString,
      1,
      nil
    ) else { throw CompositionError.imageCreationFailed }
    let properties: [CFString: Any] = [
      kCGImageDestinationLossyCompressionQuality: 1.0,
      kCGImagePropertyMakerAppleDictionary: ["17": identifier],
    ]
    CGImageDestinationAddImage(destination, image, properties as CFDictionary)
    guard CGImageDestinationFinalize(destination) else {
      throw CompositionError.imageCreationFailed
    }
  }

  private static func createPairedVideo(
    from videoURL: URL,
    metadataTemplateURL: URL,
    identifier: String,
    deviceModel: String,
    systemVersion: String,
    creationDate: String,
    outputURL: URL
  ) async throws {
    let videoAsset = AVURLAsset(url: videoURL)
    let metadataAsset = AVURLAsset(url: metadataTemplateURL)
    let duration = try await videoAsset.load(.duration)
    guard let videoTrack = try await videoAsset.loadTracks(withMediaType: .video).first else {
      throw CompositionError.missingVideoTrack
    }
    let metadataTracks = try await metadataAsset.loadTracks(withMediaType: .metadata)
    guard metadataTracks.count == 2,
          let videoReader = try? AVAssetReader(asset: videoAsset),
          let metadataReader = try? AVAssetReader(asset: metadataAsset),
          let writer = try? AVAssetWriter(outputURL: outputURL, fileType: .mov) else {
      throw CompositionError.writerCreationFailed
    }

    var transfers: [(AVAssetReaderOutput, AVAssetWriterInput, String)] = []
    for (track, reader) in [(videoTrack, videoReader)] + metadataTracks.map({ ($0, metadataReader) }) {
      guard let format = try await track.load(.formatDescriptions).first else {
        throw CompositionError.writerCreationFailed
      }
      let output = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
      output.alwaysCopiesSampleData = false
      guard reader.canAdd(output) else { throw CompositionError.readerCreationFailed }
      reader.add(output)

      let input = AVAssetWriterInput(
        mediaType: track.mediaType,
        outputSettings: nil,
        sourceFormatHint: format
      )
      input.expectsMediaDataInRealTime = false
      if track.mediaType == .video {
        input.transform = try await track.load(.preferredTransform)
      }
      guard writer.canAdd(input) else { throw CompositionError.writerCreationFailed }
      writer.add(input)
      transfers.append((output, input, "track-\(track.trackID)"))
    }

    let replacedIdentifiers: Set<AVMetadataIdentifier> = [
      .quickTimeMetadataContentIdentifier,
      .quickTimeMetadataMake,
      .quickTimeMetadataModel,
      .quickTimeMetadataSoftware,
      .quickTimeMetadataCreationDate,
    ]
    var movieMetadata = try await metadataAsset.load(.metadata).filter {
      guard let identifier = $0.identifier else { return true }
      return !replacedIdentifiers.contains(identifier)
    }
    movieMetadata.append(contentsOf: [
      metadataItem(identifier: .quickTimeMetadataContentIdentifier, value: identifier),
      metadataItem(identifier: .quickTimeMetadataMake, value: "Apple"),
      metadataItem(identifier: .quickTimeMetadataModel, value: deviceModel),
      metadataItem(identifier: .quickTimeMetadataSoftware, value: systemVersion),
      metadataItem(identifier: .quickTimeMetadataCreationDate, value: creationDate),
    ])
    writer.metadata = movieMetadata

    guard writer.startWriting(), videoReader.startReading(), metadataReader.startReading() else {
      throw CompositionError.writerCreationFailed
    }
    writer.startSession(atSourceTime: .zero)
    try await withThrowingTaskGroup(of: Void.self) { group in
      for (output, input, label) in transfers {
        group.addTask {
          try await transfer(output: output, input: input, label: label)
        }
      }
      try await group.waitForAll()
    }
    guard videoReader.status == .completed, metadataReader.status == .completed else {
      throw videoReader.error ?? metadataReader.error ?? CompositionError.sampleTransferFailed
    }
    writer.endSession(atSourceTime: duration)
    await writer.finishWriting()
    guard writer.status == .completed else {
      throw writer.error ?? CompositionError.writerCreationFailed
    }
  }

  private static func metadataItem(
    identifier: AVMetadataIdentifier,
    value: String
  ) -> AVMetadataItem {
    let item = AVMutableMetadataItem()
    item.identifier = identifier
    item.value = value as NSString
    item.dataType = "com.apple.metadata.datatype.UTF-8"
    return item
  }

  private static func transfer(
    output: AVAssetReaderOutput,
    input: AVAssetWriterInput,
    label: String
  ) async throws {
    try await withCheckedThrowingContinuation { continuation in
      let queue = DispatchQueue(label: "com.qingjing.bizhi.livephoto.\(label)")
      input.requestMediaDataWhenReady(on: queue) {
        while input.isReadyForMoreMediaData {
          guard let sample = output.copyNextSampleBuffer() else {
            input.markAsFinished()
            continuation.resume()
            return
          }
          guard input.append(sample) else {
            input.markAsFinished()
            continuation.resume(throwing: CompositionError.sampleTransferFailed)
            return
          }
        }
      }
    }
  }
}
