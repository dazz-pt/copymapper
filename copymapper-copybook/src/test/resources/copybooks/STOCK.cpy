      * GET-STOCK: ONE STOCK ITEM AND THE ADDRESS OF ITS STORE.
      * IT HAS A SYNC SLACK BYTE, AN INDEX ITEM, A COPY MEMBER WITH
      * REPLACING, AND LEVEL 88 ITEMS WITH THRU.
       01  GET-STOCK.
           05  STOCK-ID                 PIC X(10).
           05  STOCK-LEVEL              PIC 9(3).
               88  STOCK-LOW            VALUE 0 THRU 9.
               88  STOCK-HIGH           VALUE 500 THRU 999.
           05  STOCK-QUANTITY           PIC S9(4) COMP SYNC.
           COPY ADDRESS REPLACING ==:PFX:== BY ==STORE==.
           05  STOCK-IDX                USAGE IS INDEX.
