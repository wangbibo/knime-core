package org.knime.base.node.preproc.pmml.xml2pmml;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.xmlbeans.XmlException;
import org.dmg.pmml.PMMLDocument;
import org.knime.core.data.DataCell;
import org.knime.core.data.DataColumnSpec;
import org.knime.core.data.DataColumnSpecCreator;
import org.knime.core.data.DataRow;
import org.knime.core.data.DataTableSpec;
import org.knime.core.data.DataType;
import org.knime.core.data.MissingCell;
import org.knime.core.data.StringValue;
import org.knime.core.data.container.CellFactory;
import org.knime.core.data.container.ColumnRearranger;
import org.knime.core.data.container.SingleCellFactory;
import org.knime.core.data.xml.PMMLCell;
import org.knime.core.data.xml.PMMLCellFactory;
import org.knime.core.data.xml.XMLValue;
import org.knime.core.node.BufferedDataTable;
import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.ExecutionContext;
import org.knime.core.node.ExecutionMonitor;
import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.NodeLogger;
import org.knime.core.node.NodeModel;
import org.knime.core.node.NodeSettingsRO;
import org.knime.core.node.NodeSettingsWO;
import org.knime.core.node.defaultnodesettings.SettingsModelBoolean;
import org.knime.core.node.defaultnodesettings.SettingsModelString;
import org.knime.core.node.util.CheckUtils;
import org.knime.core.pmml.PMMLValidator;
import org.w3c.dom.Document;

/**
 * This is the model implementation of XML2PMML.
 *
 *
 * @author Alexander Fillbrunn
 */
public class XML2PMMLNodeModel extends NodeModel {
    private static final NodeLogger LOGGER = NodeLogger.getLogger(XML2PMMLNodeModel.class);


    /**
     * Constructor for the node model.
     */
    protected XML2PMMLNodeModel() {
        super(1, 1);
    }

    /**
     * Config key for the replace column setting.
     */
    static final String CFG_REPLACE_COLUMN = "replaceColumn";

    /**
     * Config key for the xml column name setting.
     */
    static final String CFG_XML_COLUMN_NAME = "xmlColumnName";

    /**
     * Config key for the new column name setting.
     */
    static final String CFG_NEW_COLUMN_NAME = "newColumnName";

    /**
     * Config key for the fail-on-invalid setting.
     */
    static final String CFG_FAIL_ON_INVALID = "failOnInvalid";

    /**
     * Creates a new SettingsModelString for the xml column name setting.
     * @return a SettingsModelString
     */
    static SettingsModelString createXMLColumnNameSettingsMode() {
        return new SettingsModelString(CFG_XML_COLUMN_NAME, null);
    }

    /**
     * Creates a new SettingsModelString for the new column name setting.
     * @return a SettingsModelString
     */
    static SettingsModelString createNewColumnNameSettingsMode() {
        return new SettingsModelString(CFG_NEW_COLUMN_NAME, "pmml");
    }

    /**
     * Creates a new SettingsModelString for the replace xml column setting.
     * @return a SettingsModelString
     */
    static SettingsModelBoolean createReplaceXMLColumnSettingsMode() {
        return new SettingsModelBoolean(CFG_REPLACE_COLUMN, false);
    }

    /**
     * Creates a new SettingsModelString for the fail-on-invalid setting.
     * @return a SettingsModelString
     */
    static SettingsModelBoolean createFailOnInvalidSettingsMode() {
        return new SettingsModelBoolean(CFG_FAIL_ON_INVALID, false);
    }

    private SettingsModelString m_xmlColumnName = createXMLColumnNameSettingsMode();

    private SettingsModelString m_newColumnName = createNewColumnNameSettingsMode();

    private SettingsModelBoolean m_replaceColumn = createReplaceXMLColumnSettingsMode();

    private SettingsModelBoolean m_failOnInvalid = createFailOnInvalidSettingsMode();

    private final AtomicInteger m_failCounter = new AtomicInteger();

    /**
     * {@inheritDoc}
     */
    @Override
    protected BufferedDataTable[] execute(final BufferedDataTable[] inData,
            final ExecutionContext exec) throws Exception {

        ColumnRearranger colRe = createColRearranger(inData[0].getDataTableSpec());
        BufferedDataTable resultTable = exec.createColumnRearrangeTable(inData[0], colRe, exec);

        int rowCount = resultTable.getRowCount();

        if ((rowCount > 0) && (m_failCounter.get() == rowCount)) {
            setWarningMessage("Failed to convert all documents");
        } else if (m_failCounter.get() > 0) {
            setWarningMessage("Failed to convert " + m_failCounter + " of " + rowCount + " documents");
        }

        return new BufferedDataTable[]{resultTable};
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void reset() {
        m_failCounter.set(0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected DataTableSpec[] configure(final DataTableSpec[] inSpecs)
            throws InvalidSettingsException {
        ColumnRearranger rearranger = createColRearranger(inSpecs[0]);
        return new DataTableSpec[]{rearranger.createSpec()};
    }

    private void guessDefaultXMLColumn(final DataTableSpec spec) throws InvalidSettingsException {
        String winColumn = null;
        for (DataColumnSpec col : spec) {
            DataType type = col.getType();
            if (type.isCompatible(XMLValue.class)) {
                //we found a xml value so break.
                winColumn = col.getName();
                break;
            }
        }
        CheckUtils.checkSetting(winColumn != null, "No valid input column available");
        m_xmlColumnName.setStringValue(winColumn);
        m_newColumnName.setStringValue(DataTableSpec.getUniqueColumnName(spec, "PMML from " + winColumn));
    }

    private ColumnRearranger createColRearranger(final DataTableSpec spec)
            throws InvalidSettingsException {
            if (m_xmlColumnName.getStringValue() == null) {
                guessDefaultXMLColumn(spec);
            }

            String xmlColName = m_xmlColumnName.getStringValue();
            String newColName = m_newColumnName.getStringValue();

            final int colIndex = spec.findColumnIndex(xmlColName);
            CheckUtils.checkSetting(colIndex >= 0, "Column: '%s' does not exist anymore.", xmlColName);
            final DataColumnSpec colSpec = spec.getColumnSpec(colIndex);
            CheckUtils.checkSetting(colSpec.getType().isCompatible(StringValue.class),
                "Selected column '%s' is not string/xml-compatible", xmlColName);

            DataColumnSpecCreator colSpecCreator;

            if (newColName != null && !m_replaceColumn.getBooleanValue()) {
                String newName = DataTableSpec.getUniqueColumnName(spec, newColName);
                colSpecCreator = new DataColumnSpecCreator(newName, PMMLCell.TYPE);
            } else {
                colSpecCreator = new DataColumnSpecCreator(colSpec);
                colSpecCreator.setType(PMMLCell.TYPE);
                colSpecCreator.removeAllHandlers();
                colSpecCreator.setDomain(null);
            }

            DataColumnSpec outColumnSpec = colSpecCreator.createSpec();
            ColumnRearranger rearranger = new ColumnRearranger(spec);
            CellFactory fac = new SingleCellFactory(outColumnSpec) {
                @Override
                public DataCell getCell(final DataRow row) {
                    DataCell cell = row.getCell(colIndex);
                    if (cell.isMissing()) {
                        return DataType.getMissingCell();
                    } else {
                        Document d = (Document)((XMLValue)cell).getDocument().cloneNode(true);
                        String failure = null;
                        try {
                            PMMLDocument doc = PMMLDocument.Factory.parse(d);
                            if (!PMMLValidator.validatePMML(doc).isEmpty()) {
                                failure = "Document does not conform to PMML schema";
                            }
                        } catch (XmlException e) {
                            if (!m_failOnInvalid.getBooleanValue()) {
                                LOGGER.error("Invalid PMML in row " + row.getKey() + ": " + e.getMessage(), e);
                            }
                            failure = e.getMessage();
                        }

                        if (failure != null) {
                            m_failCounter.incrementAndGet();
                            if (m_failOnInvalid.getBooleanValue()) {
                                throw new RuntimeException("Invalid PMML in row " + row.getKey() + ": " + failure);
                            } else {
                                return new MissingCell(failure);
                            }
                        } else {
                            return PMMLCellFactory.create(d);
                        }
                    }
                }
            };
            if (m_replaceColumn.getBooleanValue()) {
                rearranger.replace(fac, colIndex);
            } else {
                rearranger.append(fac);
            }
            return rearranger;
        }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void saveSettingsTo(final NodeSettingsWO settings) {
        m_xmlColumnName.saveSettingsTo(settings);
        m_newColumnName.saveSettingsTo(settings);
        m_replaceColumn.saveSettingsTo(settings);
        m_failOnInvalid.saveSettingsTo(settings);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void loadValidatedSettingsFrom(final NodeSettingsRO settings)
            throws InvalidSettingsException {
        m_xmlColumnName.loadSettingsFrom(settings);
        m_newColumnName.loadSettingsFrom(settings);
        m_replaceColumn.loadSettingsFrom(settings);
        m_failOnInvalid.loadSettingsFrom(settings);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void validateSettings(final NodeSettingsRO settings)
            throws InvalidSettingsException {
        m_xmlColumnName.validateSettings(settings);
        m_newColumnName.validateSettings(settings);
        m_replaceColumn.validateSettings(settings);
        m_failOnInvalid.validateSettings(settings);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void loadInternals(final File internDir,
            final ExecutionMonitor exec) throws IOException,
            CanceledExecutionException {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void saveInternals(final File internDir,
            final ExecutionMonitor exec) throws IOException,
            CanceledExecutionException {
    }
}

