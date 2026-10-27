import { describe, expect, it } from "vitest";

import { fiveStarValue, idOf, productPath, stars } from "./format";

describe("format", () => {
  it("finds a node's id in its IRI", () => {
    expect(idOf("http://localhost:3000/api/storage/products/12")).toBe("12");
    expect(productPath("http://localhost:3000/api/storage/products/12")).toBe("/products/12");
  });

  it("draws ratings to the nearest half star", () => {
    expect(stars(3.5)).toEqual(["full", "full", "full", "half", "empty"]);
    expect(stars(3.7)).toEqual(["full", "full", "full", "half", "empty"]);
    expect(stars(3.8)).toEqual(["full", "full", "full", "full", "empty"]);
    expect(stars(5)).toEqual(Array(5).fill("full"));
    expect(stars(9)).toEqual(Array(5).fill("full"));
    expect(stars(0)).toEqual(Array(5).fill("empty"));
  });

  it("scales ratings to 1-5, as the storage service averages them", () => {
    expect(fiveStarValue({ ratingValue: 4 })).toBe(4);
    expect(fiveStarValue({ ratingValue: 2, worstRating: 0, bestRating: 10 })).toBeCloseTo(1.8);
  });
});
