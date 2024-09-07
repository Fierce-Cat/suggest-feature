"use client"

import { useState, useEffect } from "react";
import { Icons } from "./icons"
import { cn } from "@/lib/utils";

export default function Loading({ className = "" }) {
  const [showLoading, setShowLoading] = useState(false);

  // Dont show loading spinner for at least 1.5 seconds
  useEffect(() => {
    const timer = setTimeout(() => {
      setShowLoading(true);
    }, 1500); // Adjust the delay here (in milliseconds)

    return () => clearTimeout(timer);
  }, []);

  if (!showLoading) {
    return null;
  }
  return (
    <div className={cn("flex items-center justify-center h-screen", className)}>
      <div className="flex flex-col items-center gap-4">
        <Icons.spinner className="h-12 w-12 animate-spin" />
        <p>Loading...</p>
      </div>
    </div>
  )
}
