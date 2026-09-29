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
    case imageCreationFailed
    case sampleTransferFailed
  }

  static func create(
    from sourceURL: URL,
    metadataTemplateURL: URL,
    targetSize: CGSize,
    directory: URL
  ) async throws -> NativeLivePhotoResources {
    let identifier = UUID().uuidString
    let canvasURL = directory.appendingPathComponent("canvas.mov")
    let photoURL = directory.appendingPathComponent("photo.heic")
    let pairedVideoURL = directory.appendingPathComponent("paired.mov")

    try await createCanvasVideo(from: sourceURL, targetSize: targetSize, outputURL: canvasURL)
    try await createImage(from: canvasURL, identifier: identifier, outputURL: photoURL)
    try await createPairedVideo(
      from: canvasURL,
      metadataTemplateURL: metadataTemplateURL,
      identifier: identifier,
      outputURL: pairedVideoURL
    )
    return NativeLivePhotoResources(photoURL: photoURL, videoURL: pairedVideoURL)
  }

  private static func createCanvasVideo(
    from sourceURL: URL,
    targetSize: CGSize,
    outputURL: URL
  ) async throws {
    let asset = AVURLAsset(url: sourceURL)
    guard let videoTrack = try await asset.loadTracks(withMediaType: .video).first else {
      throw CompositionError.missingVideoTrack
    }
    let preferredTransform = try await videoTrack.load(.preferredTransform)
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
    let compression: [String: Any] = [
      AVVideoAverageBitRateKey: 20_000_000,
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
    guard readerOutput.copyNextSampleBuffer() == nil, reader.status == .completed else {
      throw CompositionError.frameCountInvalid
    }

    writerInput.markAsFinished()
    writer.endSession(atSourceTime: CMTime(value: 60, timescale: 60))
    await writer.finishWriting()
    guard writer.status == .completed else {
      throw writer.error ?? CompositionError.writerCreationFailed
    }
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
      at: CMTime(value: 30, timescale: 60),
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

    let contentIdentifier = AVMutableMetadataItem()
    contentIdentifier.identifier = .quickTimeMetadataContentIdentifier
    contentIdentifier.value = identifier as NSString
    contentIdentifier.dataType = "com.apple.metadata.datatype.UTF-8"
    var movieMetadata = try await metadataAsset.load(.metadata).filter {
      $0.identifier != .quickTimeMetadataContentIdentifier
    }
    movieMetadata.append(contentIdentifier)
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
