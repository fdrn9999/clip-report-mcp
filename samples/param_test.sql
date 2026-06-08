SELECT A.EMP_NM, A.SALY_YM, A.DEPT_CD
  FROM TB_EMP A
 WHERE A.EMP_NM  = #{empNm}
   AND A.SALY_YM = #{salyYm}
   AND A.DEPT_CD = #{dept_cd}
