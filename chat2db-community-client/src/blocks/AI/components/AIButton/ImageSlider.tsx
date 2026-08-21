import React, { useState, useEffect, useRef } from 'react';
import aiClick1 from '@/assets/img/ai/ai-click/1.png';
import aiClick2 from '@/assets/img/ai/ai-click/2.png';
import aiClick3 from '@/assets/img/ai/ai-click/3.png';
import aiClick4 from '@/assets/img/ai/ai-click/4.png';
import aiClick5 from '@/assets/img/ai/ai-click/5.png';
import aiClick6 from '@/assets/img/ai/ai-click/6.png';
import aiClick7 from '@/assets/img/ai/ai-click/7.png';
import aiClick8 from '@/assets/img/ai/ai-click/8.png';
import aiClick9 from '@/assets/img/ai/ai-click/9.png';

const clickImages = [aiClick1, aiClick2, aiClick3, aiClick4, aiClick5, aiClick6, aiClick7, aiClick8, aiClick9];
const hoverImages = [];
interface ImageSliderProps {
  type: 'click' | 'hover';
  showPanel: boolean;
}

const ImageSlider: React.FC<ImageSliderProps> = ({ type, showPanel }) => {
  const images = type === 'click' ? clickImages : hoverImages;

  const [currentFrame, setCurrentFrame] = useState(0);
  const isPlaying = useRef(false);
  const totalFrames = images.length;
  const intervalRef = useRef<NodeJS.Timeout | null>(null); // is used to store interval ID

  useEffect(() => {
    if (showPanel) {
      play();
    } else {
      reversePlay();
    }
  }, [showPanel]);

  const handleClick = () => {
    if (isPlaying.current) {
      // reverse playback
      isPlaying.current = false;
      reversePlay();
    } else {
      // forward playback
      isPlaying.current = true;
      play();
    }
  };

  const play = () => {
    if (intervalRef.current) clearInterval(intervalRef.current); // clears the previous interval
    intervalRef.current = setInterval(() => {
      setCurrentFrame((prevFrame) => {
        if (prevFrame < totalFrames - 1) {
          return prevFrame + 1;
        } else {
          clearInterval(intervalRef.current!);
          return prevFrame;
        }
      });
    }, 100); // 100 milliseconds between frames
  };

  const reversePlay = () => {
    if (intervalRef.current) clearInterval(intervalRef.current); // clears the previous interval
    intervalRef.current = setInterval(() => {
      setCurrentFrame((prevFrame) => {
        if (prevFrame > 0) {
          return prevFrame - 1;
        } else {
          clearInterval(intervalRef.current!);
          return prevFrame;
        }
      });
    }, 100); // 100 milliseconds between frames
  };

  useEffect(() => {
    // component is uninstalled
    return () => {
      isPlaying.current = false;
      if (intervalRef.current) clearInterval(intervalRef.current); // clear interval
    };
  }, []);

  return (
    <img
      onClick={handleClick}
      src={images[currentFrame]}
      alt={`Frame ${currentFrame}`}
      style={{ width: '100%', height: '100%' }}
    />
  );
};

export default ImageSlider;
