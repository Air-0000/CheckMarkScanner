package com.parking.checkscanner;
import java.util.Map;
public class SerialNumberResolverTest {
 public static void main(String[] args) {
  check(Map.of(116, 25, 117, 2),116);
  check(Map.of(116, 2),116);
  check(Map.of(116, 1),-1);
  check(Map.of(116, 3, 117, 3),-1);
  check(Map.of(1, 2, 116, 8),116);
  check(Map.of(),-1);
  check(Map.of(-1, 20, 501, 20, 154, 4),154);
  System.out.println("7 serial vote cases passed");
 }
 static void check(Map<Integer,Integer> votes,int expected) {
  if(SerialNumberResolver.resolve(votes)!=expected) throw new AssertionError(votes);
 }
}
