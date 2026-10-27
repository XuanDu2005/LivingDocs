import { useCallback, useEffect, useRef, useState } from 'react';
import { Minus, Move, Plus, RotateCcw } from 'lucide-react';
import { useTranslation } from 'react-i18next';

import { Button } from '../ui/button';
import { cn } from '../../lib/utils';
import { describeError } from '../../services/auth';

export interface AvatarCropperProps {
  /** The image the user picked, as an object URL or data URL. */
  src: string;
  /** Called when the user confirms — receives the cropped PNG as a File. */
  onCrop: (file: File) => void | Promise<void>;
  /** Called when the user cancels. */
  onCancel: () => void;
}

/** Size of the visible crop window in CSS pixels. */
const CROP_SIZE = 288;
/** Edge length of the exported PNG. */
const OUTPUT_SIZE = 512;
/** Bounds of the zoom slider. */
const MIN_ZOOM = 1;
const MAX_ZOOM = 3;

/**
 * Interactive avatar cropper.
 *
 * <p>The user drags the image to reposition it and uses the slider to
 * zoom. The circular mask shows exactly which part of the picture will
 * become the avatar. On confirm, the visible square is rendered onto a
 * canvas and exported as a PNG {@link File} — the crop happens entirely
 * in the browser, so the user never uploads the original picture.
 */
export function AvatarCropper({ src, onCrop, onCancel }: AvatarCropperProps) {
  const { t } = useTranslation();
  const imageRef = useRef<HTMLImageElement>(null);
  const [zoom, setZoom] = useState(1);
  const [offset, setOffset] = useState({ x: 0, y: 0 });
  const [dragging, setDragging] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const dragStart = useRef<{ pointerX: number; pointerY: number; originX: number; originY: number } | null>(null);

  const [natural, setNatural] = useState<{ width: number; height: number } | null>(null);

  /**
   * The decoded source image.
   *
   * <p>We keep a reference to the detached {@link Image} element rather
   * than the one rendered in the crop window. The visible {@code <img>}
   * may still be decoding (or may have been resized by CSS), while the
   * probe has already fired {@code onload} — drawing from it is
   * therefore reliable.
   */
  const sourceImageRef = useRef<HTMLImageElement | null>(null);

  // Reset the view whenever a new image is loaded.
  useEffect(() => {
    setZoom(1);
    setOffset({ x: 0, y: 0 });
    setNatural(null);
    setError(null);
  }, [src]);

  /**
   * Read the intrinsic size of the picture with a detached {@link Image}.
   *
   * <p>We cannot rely on the {@code <img>} element's own {@code onLoad}
   * event for this: the element is only rendered once we already know
   * the dimensions, which would deadlock. Decoding the size separately
   * breaks the cycle.
   */
  useEffect(() => {
    let cancelled = false;
    const probe = new Image();
    probe.onload = () => {
      if (!cancelled) {
        sourceImageRef.current = probe;
        setNatural({ width: probe.naturalWidth, height: probe.naturalHeight });
      }
    };
    probe.onerror = () => {
      if (!cancelled) {
        setNatural({ width: CROP_SIZE, height: CROP_SIZE });
      }
    };
    probe.src = src;
    return () => {
      cancelled = true;
    };
  }, [src]);

  /**
   * Scale at which the image exactly covers the crop window. Anything
   * above 1 means the image overflows and can be panned.
   */
  const coverScale = natural
    ? Math.max(CROP_SIZE / natural.width, CROP_SIZE / natural.height)
    : 1;
  const totalScale = coverScale * zoom;

  const displayWidth = natural ? natural.width * totalScale : 0;
  const displayHeight = natural ? natural.height * totalScale : 0;

  /**
   * Clamps the pan so the image always covers the crop window — no
   * empty gaps can appear at the edges.
   */
  const clamp = useCallback(
    (x: number, y: number) => {
      const minX = CROP_SIZE - displayWidth;
      const minY = CROP_SIZE - displayHeight;
      return {
        x: Math.min(0, Math.max(minX, x)),
        y: Math.min(0, Math.max(minY, y)),
      };
    },
    [displayWidth, displayHeight],
  );

  useEffect(() => {
    setOffset((o) => clamp(o.x, o.y));
  }, [clamp]);

  function handlePointerDown(e: React.PointerEvent<HTMLDivElement>) {
    (e.target as HTMLElement).setPointerCapture?.(e.pointerId);
    setDragging(true);
    dragStart.current = {
      pointerX: e.clientX,
      pointerY: e.clientY,
      originX: offset.x,
      originY: offset.y,
    };
  }

  function handlePointerMove(e: React.PointerEvent<HTMLDivElement>) {
    if (!dragging || !dragStart.current) return;
    const dx = e.clientX - dragStart.current.pointerX;
    const dy = e.clientY - dragStart.current.pointerY;
    const next = clamp(dragStart.current.originX + dx, dragStart.current.originY + dy);
    setOffset(next);
  }

  function handlePointerUp(e: React.PointerEvent<HTMLDivElement>) {
    (e.target as HTMLElement).releasePointerCapture?.(e.pointerId);
    setDragging(false);
    dragStart.current = null;
  }

  function resetView() {
    setZoom(1);
    setOffset({ x: 0, y: 0 });
  }

  /**
   * Renders the visible crop window onto a canvas and hands the result
   * back as a PNG file.
   */
  async function handleConfirm() {
    const image = sourceImageRef.current ?? imageRef.current;
    if (!image || !natural) return;

    setSaving(true);
    try {
      // Make sure the picture is fully decoded before we sample it —
      // drawing from a half-decoded image yields a blank canvas.
      if (image.decode) {
        try {
          await image.decode();
        } catch {
          // decode() rejects for some blob types; the onload handler has
          // already fired at this point, so we can carry on.
        }
      }

      const canvas = document.createElement('canvas');
      canvas.width = OUTPUT_SIZE;
      canvas.height = OUTPUT_SIZE;
      const ctx = canvas.getContext('2d');
      if (!ctx) return;

      // Map the crop window (CROP_SIZE × CROP_SIZE CSS px) back to the
      // source image's own pixel coordinates.
      const srcX = (0 - offset.x) * (natural.width / displayWidth);
      const srcY = (0 - offset.y) * (natural.height / displayHeight);
      const srcW = CROP_SIZE * (natural.width / displayWidth);
      const srcH = CROP_SIZE * (natural.height / displayHeight);

      ctx.imageSmoothingQuality = 'high';
      ctx.drawImage(image, srcX, srcY, srcW, srcH, 0, 0, OUTPUT_SIZE, OUTPUT_SIZE);

      const blob = await new Promise<Blob | null>((resolve) =>
        canvas.toBlob(resolve, 'image/png', 0.92),
      );
      if (!blob) {
        setError(t('profile.avatar.cropFailed'));
        return;
      }

      const file = new File([blob], 'avatar.png', { type: 'image/png' });
      await onCrop(file);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="space-y-4">
      {/* ---- Crop window ---- */}
      <div className="flex justify-center">
        <div
          className={cn(
            'relative touch-none overflow-hidden rounded-full border-2 border-primary/60 bg-muted',
            dragging ? 'cursor-grabbing' : 'cursor-grab',
          )}
          style={{ width: CROP_SIZE, height: CROP_SIZE }}
          onPointerDown={handlePointerDown}
          onPointerMove={handlePointerMove}
          onPointerUp={handlePointerUp}
          onPointerCancel={handlePointerUp}
        >
          <img
            ref={imageRef}
            src={src}
            alt=""
            draggable={false}
            className="pointer-events-none absolute max-w-none select-none"
            style={{
              // Until the intrinsic size is known, fall back to a square
              // that exactly fills the crop window so the user always
              // sees something.
              width: natural ? displayWidth : CROP_SIZE,
              height: natural ? displayHeight : CROP_SIZE,
              // The offset is applied directly (no centring term): the
              // cover scale already makes one axis fit exactly, and the
              // other overflows and is panned. This keeps the preview
              // pixel-identical to what {@link handleConfirm} exports.
              left: offset.x,
              top: offset.y,
            }}
          />

          {/* Grid overlay to help align the subject. */}
          <div className="pointer-events-none absolute inset-0">
            <div className="absolute inset-y-0 left-1/3 w-px bg-white/25" />
            <div className="absolute inset-y-0 left-2/3 w-px bg-white/25" />
            <div className="absolute inset-x-0 top-1/3 h-px bg-white/25" />
            <div className="absolute inset-x-0 top-2/3 h-px bg-white/25" />
          </div>
        </div>
      </div>

      <p className="flex items-center justify-center gap-1.5 text-xs text-muted-foreground">
        <Move className="h-3 w-3" />
        {t('profile.avatar.cropHint')}
      </p>

      {error && (
        <div className="rounded-md border border-destructive/50 bg-destructive/10 p-2 text-sm text-destructive">
          {error}
        </div>
      )}

      {/* ---- Zoom ---- */}
      <div className="flex items-center gap-3">
        <Minus className="h-4 w-4 shrink-0 text-muted-foreground" />
        <input
          type="range"
          min={MIN_ZOOM}
          max={MAX_ZOOM}
          step={0.01}
          value={zoom}
          onChange={(e) => setZoom(Number(e.target.value))}
          className="h-1.5 w-full cursor-pointer appearance-none rounded-full bg-muted accent-primary"
          aria-label={t('profile.avatar.zoom')}
        />
        <Plus className="h-4 w-4 shrink-0 text-muted-foreground" />
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="h-8 w-8 shrink-0"
          onClick={resetView}
          title={t('profile.avatar.reset')}
        >
          <RotateCcw className="h-4 w-4" />
        </Button>
      </div>

      {/* ---- Actions ---- */}
      <div className="flex justify-end gap-2">
        <Button type="button" variant="outline" onClick={onCancel} disabled={saving}>
          {t('common.cancel')}
        </Button>
        <Button type="button" onClick={handleConfirm} disabled={saving || !natural}>
          {saving ? t('common.actions.saving') : t('profile.avatar.applyCrop')}
        </Button>
      </div>
    </div>
  );
}
