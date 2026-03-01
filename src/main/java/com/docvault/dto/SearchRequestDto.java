package com.docvault.dto;

public class SearchRequestDto {
    private String q;
    private String department;
    private String tier;
    private String dateFrom;
    private String dateTo;
    private Integer from;
    private Integer size;

    public String  getQ()          { return q; }
    public void    setQ(String v)  { this.q = v; }
    public String  getDepartment() { return department; }
    public void    setDepartment(String v) { this.department = v; }
    public String  getTier()       { return tier; }
    public void    setTier(String v) { this.tier = v; }
    public String  getDateFrom()   { return dateFrom; }
    public void    setDateFrom(String v) { this.dateFrom = v; }
    public String  getDateTo()     { return dateTo; }
    public void    setDateTo(String v) { this.dateTo = v; }
    public Integer getFrom()       { return from; }
    public void    setFrom(Integer v) { this.from = v; }
    public Integer getSize()       { return size; }
    public void    setSize(Integer v) { this.size = v; }
}
